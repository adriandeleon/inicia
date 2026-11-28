package dev.inicia.engine;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Arch;
import dev.inicia.catalog.Installer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Decides, per app, whether to install, upgrade or leave it, from what's installed. */
public final class Planner {

    private Planner() {
    }

    public static List<Step> plan(List<App> apps, List<InstalledApp> installed, Arch machine, boolean force) {
        List<Step> steps = new ArrayList<>();
        for (App app : apps) {
            steps.add(step(app, installed, machine, force));
        }
        return steps;
    }

    static Step step(App app, List<InstalledApp> installed, Arch machine, boolean force) {
        Optional<Installer> installer = app.installerFor(machine);
        Optional<String> found = installedVersion(app, installed);
        if (installer.isEmpty()) {
            return new Step(app, Step.Action.NO_INSTALLER, installer, found);
        }
        if (found.isEmpty()) {
            return new Step(app, Step.Action.INSTALL, installer, found);
        }
        if (force) {
            return new Step(app, Step.Action.REINSTALL, installer, found);
        }
        if (app.version().isEmpty()) {
            return new Step(app, Step.Action.PRESENT, installer, found);
        }
        // an entry with no DisplayVersion can't be compared: installing the catalog's version is the safe side
        boolean older = found.get().isEmpty() || Versions.compare(found.get(), app.version().get()) < 0;
        return new Step(app, older ? Step.Action.UPGRADE : Step.Action.UP_TO_DATE, installer, found);
    }

    /** The installed entries the app's pattern matches. */
    public static List<InstalledApp> installedEntries(App app, List<InstalledApp> installed) {
        return installed.stream().filter(e -> app.detect().matcher(e.displayName()).find()).toList();
    }

    /** The highest version among installed entries the app's pattern matches. */
    public static Optional<String> installedVersion(App app, List<InstalledApp> installed) {
        Optional<String> best = Optional.empty();
        for (InstalledApp entry : installed) {
            if (app.detect().matcher(entry.displayName()).find()) {
                if (best.isEmpty() || Versions.compare(entry.version(), best.get()) > 0) {
                    best = Optional.of(entry.version());
                }
            }
        }
        return best;
    }
}
