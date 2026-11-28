package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import dev.inicia.catalog.CatalogException;
import dev.inicia.catalog.CatalogReader;
import dev.inicia.catalog.SignedCatalog;
import dev.inicia.engine.Downloader;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where the apps come from, and how they're downloaded: the latest signed catalog from inicia's
 * site (else the one built in), catalogs of your own added to it, and the proxy server.
 */
final class CatalogOptions {

    /** The site's catalog; INICIA_CATALOG_URL points a fork's build at its own. */
    static final String LATEST = "https://adriandeleon.github.io/inicia/catalog.json";

    @Option(names = "--catalog", paramLabel = "<file|url>",
            description = "Another catalog (a file, or an https:// address) whose apps are added to inicia's; "
                    + "an app with the same id replaces inicia's entry. Repeatable.")
    List<String> catalogs = new ArrayList<>();

    @Option(names = "--built-in-catalog",
            description = "Use the catalog built into this inicia, rather than fetching the latest signed one from inicia's site.")
    boolean builtInOnly;

    @Option(names = "--proxy", paramLabel = "<host:port>",
            description = "The proxy server to download through (host:port, or http://user:password@host:port). "
                    + "Without it: the INICIA_PROXY, HTTPS_PROXY or ALL_PROXY environment variable, else Windows' proxy settings.")
    String proxy;

    private String source = "the catalog built into inicia";

    /** Which catalog the last load used, in words, for the log. */
    String source() {
        return source;
    }

    Catalog load() throws IOException, InterruptedException, CatalogException {
        return load(List.of());
    }

    /**
     * The latest signed catalog when it can be had and has every app asked for, else the built-in
     * one; your own catalogs on top of either.
     */
    Catalog load(List<String> wanted) throws IOException, InterruptedException, CatalogException {
        Proxies.apply(proxy);
        List<Catalog> own = new ArrayList<>();
        for (String c : catalogs) {
            own.add(read(c));
        }
        if (!builtInOnly) {
            Optional<SignedCatalog.Verified> latest = latest();
            if (latest.isPresent()) {
                Catalog candidate = with(latest.get().catalog(), own);
                List<String> missing = candidate.expand(wanted).stream().filter(id -> candidate.app(id).isEmpty()).toList();
                if (missing.isEmpty()) {
                    source = "the latest catalog, published " + DateTimeFormatter.ISO_LOCAL_DATE.format(
                            latest.get().published().atOffset(ZoneOffset.UTC)) + " (its signature checks out)";
                    return candidate;
                }
                source = "the catalog built into inicia (the latest one has no " + String.join(", ", missing) + ")";
            }
        }
        return with(CatalogReader.builtIn(), own);
    }

    private static Catalog with(Catalog base, List<Catalog> own) {
        Catalog catalog = base;
        for (Catalog c : own) {
            catalog = catalog.with(c);
        }
        return catalog;
    }

    /** The site's signed catalog; empty, with {@link #source} saying why, when it isn't to be used. */
    private Optional<SignedCatalog.Verified> latest() throws InterruptedException {
        Optional<PublicKey> key = SignedCatalog.builtInKey();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        URI url = URI.create(System.getenv().getOrDefault("INICIA_CATALOG_URL", LATEST));
        try {
            Downloader d = new Downloader("inicia/" + Version.get(), Duration.ofSeconds(4));
            byte[] json = d.read(url, Duration.ofSeconds(10), 8 << 20);
            byte[] sig = d.read(URI.create(url + ".sig"), Duration.ofSeconds(10), 4096);
            return Optional.of(SignedCatalog.verify(json, new String(sig, StandardCharsets.US_ASCII), key.get(), Version.built()));
        } catch (SignedCatalog.Rejected e) {
            source = "the catalog built into inicia (the latest one was refused: " + e.getMessage() + ")";
        } catch (IOException | IllegalArgumentException e) {
            source = "the catalog built into inicia (the latest couldn't be fetched: " + Downloader.describe(e) + ")";
        }
        return Optional.empty();
    }

    /**
     * These options again, for a relaunch: the elevated copy starts in System32, so every file
     * path is made absolute.
     */
    List<String> relaunchArgs() {
        List<String> args = new ArrayList<>();
        for (String c : catalogs) {
            args.add("--catalog");
            args.add(c.startsWith("https://") ? c : Path.of(c).toAbsolutePath().toString());
        }
        if (builtInOnly) {
            args.add("--built-in-catalog");
        }
        if (proxy != null) {
            args.add("--proxy");
            args.add(proxy);
        }
        return args;
    }

    static Catalog read(String source) throws IOException, InterruptedException, CatalogException {
        if (source.startsWith("https://")) {
            Path tmp = Files.createTempFile("inicia-catalog-", ".json");
            try {
                new Downloader("inicia/" + Version.get()).fetch(URI.create(source), tmp);
                return CatalogReader.read(source, Files.readString(tmp));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        if (source.startsWith("http://")) {
            throw new IOException(source + ": a catalog is only read over https");
        }
        return CatalogReader.read(Path.of(source));
    }

    /**
     * The apps for these ids ("all" and "cat-…" included), in order, with what they require
     * first; unknown ids are reported together.
     */
    static List<App> resolve(Catalog catalog, List<String> ids) throws IOException {
        List<App> apps = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String id : catalog.expand(ids)) {
            catalog.app(id).ifPresentOrElse(app -> {
                if (!apps.contains(app)) {
                    apps.add(app);
                }
            }, () -> unknown.add(id));
        }
        if (!unknown.isEmpty()) {
            throw new IOException("not in the catalog: " + String.join(", ", unknown)
                    + " (inicia list shows what is)");
        }
        List<String> missing = catalog.missingRequirements();
        if (!missing.isEmpty()) {
            throw new IOException(String.join("; ", missing));
        }
        return catalog.withRequirements(apps);
    }
}
