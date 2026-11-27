package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import dev.inicia.catalog.CatalogException;
import dev.inicia.catalog.CatalogReader;
import dev.inicia.engine.Downloader;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** --catalog: catalogs of your own, added to (and overriding) the built-in one. */
final class CatalogOptions {

    @Option(names = "--catalog", paramLabel = "<file|url>",
            description = "Another catalog (a file, or an https:// address) whose apps are added to the built-in ones; "
                    + "an app with the same id replaces the built-in entry. Repeatable.")
    List<String> catalogs = new ArrayList<>();

    Catalog load() throws IOException, InterruptedException, CatalogException {
        Catalog catalog = CatalogReader.builtIn();
        for (String source : catalogs) {
            catalog = catalog.with(read(source));
        }
        return catalog;
    }

    /** The --catalog values with files made absolute, for a relaunch that starts in another folder. */
    List<String> absolute() {
        return catalogs.stream().map(s -> s.startsWith("https://") ? s : Path.of(s).toAbsolutePath().toString()).toList();
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

    /** The apps for these ids, in order; unknown ids are reported together. */
    static List<App> resolve(Catalog catalog, List<String> ids) throws IOException {
        List<App> apps = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String id : ids) {
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
        return apps;
    }
}
