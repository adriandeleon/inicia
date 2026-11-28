package dev.inicia.cli;

import dev.inicia.catalog.Catalog;
import dev.inicia.catalog.CatalogException;
import dev.inicia.catalog.SignedCatalog;
import dev.inicia.engine.Downloader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Checks catalog files of your own and lists every problem found.")
final class CheckCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<file|url>", arity = "1..*", description = "Catalogs to check.")
    List<String> sources;

    @Option(names = "--signed",
            description = "Also check each catalog's signature (the file or address with .sig added) against the key "
                    + "built into inicia, as inicia does with the catalog on its site.")
    boolean signed;

    @Override
    public Integer call() throws Exception {
        int bad = 0;
        for (String source : sources) {
            try {
                Catalog catalog = CatalogOptions.read(source);
                // requirements may name built-in apps, so they're checked against both together
                List<String> missing = dev.inicia.catalog.CatalogReader.builtIn().with(catalog).missingRequirements();
                if (!missing.isEmpty()) {
                    bad++;
                    System.out.println(source + ": " + missing.size() + " problem" + (missing.size() == 1 ? "" : "s"));
                    missing.forEach(p -> System.out.println("  " + p));
                    continue;
                }
                System.out.println(source + ": ok, " + catalog.apps().size() + " apps");
                if (signed && !signature(source)) {
                    bad++;
                }
            } catch (CatalogException e) {
                bad++;
                System.out.println(source + ": " + e.problems().size() + " problem" + (e.problems().size() == 1 ? "" : "s"));
                for (String p : e.problems()) {
                    System.out.println("  " + p);
                }
            }
        }
        return bad == 0 ? 0 : 2;
    }

    private static boolean signature(String source) throws IOException, InterruptedException {
        Optional<PublicKey> key = SignedCatalog.builtInKey();
        if (key.isEmpty()) {
            System.out.println("  signature: this inicia has no catalog key built in");
            return false;
        }
        try {
            SignedCatalog.Verified v = SignedCatalog.verify(bytes(source), new String(bytes(source + ".sig"), StandardCharsets.US_ASCII),
                    key.get(), Instant.EPOCH);
            System.out.println("  signature: checks out, published " + v.published()
                    + (v.published().isBefore(Version.built()) ? " (before this inicia was built: it would keep its own)" : ""));
            return true;
        } catch (SignedCatalog.Rejected e) {
            System.out.println("  signature: refused, " + e.getMessage());
            return false;
        }
    }

    private static byte[] bytes(String source) throws IOException, InterruptedException {
        if (source.startsWith("https://")) {
            return new Downloader("inicia/" + Version.get()).read(URI.create(source), Duration.ofSeconds(30), 8 << 20);
        }
        return Files.readAllBytes(Path.of(source));
    }
}
