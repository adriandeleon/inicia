package dev.inicia.cli;

import dev.inicia.catalog.Catalog;
import dev.inicia.catalog.CatalogException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Checks catalog files of your own and lists every problem found.")
final class CheckCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<file|url>", arity = "1..*", description = "Catalogs to check.")
    List<String> sources;

    @Override
    public Integer call() throws Exception {
        int bad = 0;
        for (String source : sources) {
            try {
                Catalog catalog = CatalogOptions.read(source);
                System.out.println(source + ": ok, " + catalog.apps().size() + " apps");
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
}
