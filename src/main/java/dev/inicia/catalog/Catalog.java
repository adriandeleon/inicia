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
