package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import dev.inicia.engine.Planner;
import dev.inicia.engine.Step;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Shows which catalog apps are installed here, and which have a newer version in the catalog.")
final class StatusCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<id>", arity = "0..*", description = "Apps to show (default: all that are installed).")
    List<String> ids = new ArrayList<>();

    @Mixin
    CatalogOptions catalog = new CatalogOptions();

    @Override
    public Integer call() throws Exception {
        Catalog cat = catalog.load();
        List<App> apps = ids.isEmpty() ? cat.apps() : CatalogOptions.resolve(cat, ids);
        List<Step> steps = Planner.plan(apps, Platform.installed(), Platform.arch(), false);
        if (ids.isEmpty()) {
            steps = steps.stream().filter(s -> s.installed().isPresent()).toList();
            if (steps.isEmpty()) {
                System.out.println("None of the catalog's apps are installed here.");
                return 0;
            }
        }
        int idWidth = steps.stream().mapToInt(s -> s.app().id().length()).max().orElse(0);
        for (Step s : steps) {
            String state = switch (s.action()) {
                case INSTALL -> "not installed";
                case UPGRADE -> s.installed().orElse("?") + " installed, " + s.app().version().orElse("?") + " available";
                case UP_TO_DATE, PRESENT -> s.installed().filter(v -> !v.isEmpty()).orElse("installed") + ", up to date";
                default -> s.action().label();
            };
            System.out.printf("  %-" + idWidth + "s  %s%n", s.app().id(), state);
        }
        return 0;
    }
}
