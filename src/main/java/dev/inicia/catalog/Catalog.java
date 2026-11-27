package dev.inicia.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The apps inicia can install, by id, in catalog order. */
public final class Catalog {

    private final Map<String, App> apps;

    public Catalog(Collection<App> apps) {
        Map<String, App> byId = new LinkedHashMap<>();
        for (App app : apps) {
            byId.put(app.id(), app);
        }
        this.apps = byId;
    }

    public List<App> apps() {
        return List.copyOf(apps.values());
    }

    public Optional<App> app(String id) {
        return Optional.ofNullable(apps.get(id));
    }

    /** Selects every app, on the command line and in an exe name: inicia_all.exe. */
    public static final String ALL = "all";
    /** Prefix of a category's selector: cat-developer-tools. */
    public static final String CATEGORY_PREFIX = "cat-";

    /** Prefix of a suite's selector: suite-vitrina. */
    public static final String SUITE_PREFIX = "suite-";

    /** The selector for a category's apps: "Developer tools" → cat-developer-tools. */
    public static String categoryToken(String category) {
        return CATEGORY_PREFIX + slug(category);
    }

    /** The selector for a suite's apps: "Vitrina" → suite-vitrina. */
    public static String suiteToken(String suite) {
        return SUITE_PREFIX + slug(suite);
    }

    private static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /** Whether an id is one of the group selectors, which no app may use as its id. */
    public static boolean isSelector(String id) {
        return id.equals(ALL) || id.startsWith(CATEGORY_PREFIX) || id.startsWith(SUITE_PREFIX);
    }

    /**
     * The ids with {@link #ALL}, category and suite selectors replaced by the apps they stand for,
     * in catalog order, each id once. Anything else is kept as it is, unknown ids included, for
     * the caller to report.
     */
    public List<String> expand(List<String> ids) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            List<String> group = id.equals(ALL) ? List.copyOf(apps.keySet())
                    : id.startsWith(CATEGORY_PREFIX) ? apps.values().stream()
                            .filter(a -> categoryToken(a.category()).equals(id)).map(App::id).toList()
                    : id.startsWith(SUITE_PREFIX) ? apps.values().stream()
                            .filter(a -> a.suite().map(Catalog::suiteToken).filter(id::equals).isPresent())
                            .map(App::id).toList()
                    : List.of();
            if (!group.isEmpty()) {
                out.addAll(group);
            } else {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /** "app: requires x, which isn't in the catalog" for each requirement that names no app here. */
    public List<String> missingRequirements() {
        List<String> missing = new ArrayList<>();
        for (App app : apps.values()) {
            for (String r : app.requires()) {
                if (!apps.containsKey(r)) {
                    missing.add(app.id() + ": requires " + r + ", which isn't in the catalog");
                }
            }
        }
        return missing;
    }

    /**
     * The apps with everything they require, each required app placed before the first app that
     * needs it, and each app once. Requirements missing from the catalog are skipped here; {@link
     * #missingRequirements} reports them.
     */
    public List<App> withRequirements(List<App> selected) {
        List<App> ordered = new ArrayList<>();
        for (App app : selected) {
            add(app, ordered, new java.util.HashSet<>());
        }
        return ordered;
    }

    private void add(App app, List<App> ordered, java.util.Set<String> visiting) {
        if (ordered.contains(app) || !visiting.add(app.id())) {
            return;   // already placed, or a cycle
        }
        for (String r : app.requires()) {
            App required = apps.get(r);
            if (required != null) {
                add(required, ordered, visiting);
            }
        }
        ordered.add(app);
    }

    /**
     * This catalog with another's apps added; an app with an id already here replaces it in place.
     * That is how a catalog of your own overrides a built-in entry.
     */
    public Catalog with(Catalog other) {
        Map<String, App> merged = new LinkedHashMap<>(apps);
        merged.putAll(other.apps);
        return new Catalog(new ArrayList<>(merged.values()));
    }
}
