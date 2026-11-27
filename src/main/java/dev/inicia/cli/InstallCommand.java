package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import dev.inicia.engine.Downloader;
import dev.inicia.engine.Engine;
import dev.inicia.engine.Planner;
import dev.inicia.engine.ProcessRunner;
import dev.inicia.engine.Selection;
import dev.inicia.engine.Step;
import dev.inicia.windows.Elevation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "install", mixinStandardHelpOptions = true,
        description = {
                "Installs the apps, or upgrades them when the catalog has a newer version.",
                "Apps already at the catalog's version are left alone. Each download is checked",
                "against the catalog's SHA-256 and/or publisher signature before it runs."})
final class InstallCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<id>", arity = "0..*", description = "Apps to install (inicia list shows the ids).")
    List<String> ids = new ArrayList<>();

    @Option(names = {"-f", "--file"}, paramLabel = "<file>",
            description = "A list of ids, one or more per line; # starts a comment.")
    Path file;

    @Option(names = "--dry-run", description = "Show what would be done, and do nothing.")
    boolean dryRun;

    @Option(names = "--force", description = "Reinstall apps that are already installed.")
    boolean force;

    @Option(names = "--pause", hidden = true, description = "Wait for Enter before exiting (its own window).")
    boolean pause;

    @Mixin
    CatalogOptions catalog = new CatalogOptions();

    /** Started from inicia_<ids>.exe with no arguments: a double-click, most likely. */
    boolean fromFileName;

    private final PrintStream out = System.out;

    @Override
    public Integer call() throws Exception {
        try {
            return install();
        } finally {
            if (pause) {
                out.println();
                out.print("Press Enter to close this window.");
                out.flush();
                System.in.read();
                out.println();
            }
        }
    }

    private int install() throws Exception {
        List<String> wanted = new ArrayList<>(ids);
        if (file != null) {
            wanted.addAll(Selection.fromListFile(file));
        }
        if (wanted.isEmpty()) {
            out.println("Nothing to install: name apps (inicia install 7zip vlc) or a list file (-f apps.txt).");
            return 2;
        }
        Catalog cat = catalog.load();
        List<App> apps = CatalogOptions.resolve(cat, wanted);

        boolean windows = Platform.isWindows();
        if (!windows && !dryRun) {
            out.println("inicia installs Windows apps; on this system only --dry-run works.");
            return 2;
        }
        List<Step> steps = Planner.plan(apps, Platform.installed(), Platform.arch(), force);
        printPlan(steps);

        long toRun = steps.stream().filter(s -> s.action().runs()).count();
        if (dryRun || toRun == 0) {
            if (toRun == 0) {
                out.println("Nothing to do.");
            }
            return 0;
        }

        if (!Elevation.isElevated()) {
            if (!Platform.isNativeImage()) {
                out.println("Installers need administrator rights: run this from an administrator terminal.");
                return 3;
            }
            return relaunchElevated(wanted);
        }

        Path work = Files.createTempDirectory("inicia-");
        Engine engine = new Engine(new Downloader("inicia/" + Version.get()), Platform.signatures(),
                ProcessRunner.system(), Platform.installedApps(), out, work);
        out.println();
        List<Engine.Result> results = engine.run(steps);
        return summarize(results, work);
    }

    private void printPlan(List<Step> steps) {
        out.println("inicia " + Version.get() + " · " + steps.size() + (steps.size() == 1 ? " app" : " apps")
                + " · " + Platform.arch().id());
        int idWidth = steps.stream().mapToInt(s -> s.app().id().length()).max().orElse(0);
        int nameWidth = steps.stream().mapToInt(s -> title(s.app()).length()).max().orElse(0);
        for (Step s : steps) {
            String what = s.action().label() + s.installed()
                    .filter(v -> s.action() != Step.Action.INSTALL)
                    .map(v -> v.isEmpty() ? "" : " (" + v + " installed)").orElse("");
            out.printf("  %-" + idWidth + "s  %-" + nameWidth + "s  %s%n", s.app().id(), title(s.app()), what);
        }
    }

    static String title(App app) {
        return app.name() + app.version().map(v -> " " + v).orElse("");
    }

    private int relaunchElevated(List<String> wanted) throws IOException, InterruptedException {
        Path exe = Path.of(ProcessHandle.current().info().command().orElseThrow());
        List<String> args = new ArrayList<>(List.of("install"));
        args.addAll(wanted);
        // the elevated copy starts in System32: every path it gets must be absolute
        for (String c : catalog.absolute()) {
            args.add("--catalog");
            args.add(c);
        }
        if (force) {
            args.add("--force");
        }
        args.add("--pause");
        out.println();
        out.println("Installing needs administrator rights: Windows will ask, and the installs run in a new window.");
        int code = Elevation.relaunch(exe, args);
        if (code == 1223) {
            out.println("The administrator prompt was declined; nothing was installed.");
            return 3;
        }
        // the elevated window showed everything and waited for Enter; this one needn't
        pause = false;
        return code;
    }

    private int summarize(List<Engine.Result> results, Path work) throws IOException {
        long installed = results.stream().filter(r -> r.status() == Engine.Status.INSTALLED).count();
        long skipped = results.stream().filter(r -> r.status() == Engine.Status.SKIPPED).count();
        List<Engine.Result> failed = results.stream().filter(r -> r.status() == Engine.Status.FAILED).toList();
        boolean reboot = results.stream().anyMatch(Engine.Result::rebootNeeded);

        out.println();
        out.println("Done: " + installed + " installed, " + skipped + " left as they were, " + failed.size() + " failed.");
        for (Engine.Result r : failed) {
            out.println("  " + r.app().name() + ": " + r.detail());
        }
        if (reboot) {
            out.println("Restart Windows to finish installing.");
        }
        if (failed.isEmpty()) {
            deleteTree(work);
        } else {
            out.println("Downloads and logs are kept in " + work);
        }
        return failed.isEmpty() ? 0 : 1;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
