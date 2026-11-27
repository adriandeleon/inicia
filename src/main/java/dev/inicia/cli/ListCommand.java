package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "list", mixinStandardHelpOptions = true, description = "Lists the apps in the catalog, by category.")
final class ListCommand implements Callable<Integer> {

    @Mixin
    CatalogOptions catalog = new CatalogOptions();

    @Override
    public Integer call() throws Exception {
        Catalog cat = catalog.load();
        Map<String, List<App>> byCategory = cat.apps().stream()
                .collect(Collectors.groupingBy(App::category, TreeMap::new, Collectors.toList()));
        int idWidth = cat.apps().stream().mapToInt(a -> a.id().length()).max().orElse(0);
        int nameWidth = cat.apps().stream().mapToInt(a -> InstallCommand.title(a).length()).max().orElse(0);
        for (Map.Entry<String, List<App>> e : byCategory.entrySet()) {
            System.out.println(e.getKey() + "  (" + Catalog.categoryToken(e.getKey()) + ")");
            for (App app : e.getValue()) {
                String arches = app.installers().stream().map(i -> i.arch().id()).collect(Collectors.joining(" "));
                System.out.printf("  %-" + idWidth + "s  %-" + nameWidth + "s  %s%n", app.id(), InstallCommand.title(app), arches);
            }
        }
        return 0;
    }
}
