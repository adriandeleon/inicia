package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import dev.inicia.engine.Engine;
import dev.inicia.engine.Planner;
import dev.inicia.engine.Progress;
import dev.inicia.engine.Selection;
import dev.inicia.engine.Step;
import dev.inicia.windows.Elevation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "install", mixinStandardHelpOptions = true,
        description = {
                "Installs the apps, or upgrades them when the catalog has a newer version.",
                "Apps already at the catalog's version are left alone. Each download is checked",
                "against the catalog's SHA-256 and/or publisher signature before it runs.",
                "Ids can also be all, cat-<category> or suite-<suite>."})
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

    @Option(names = {"-q", "--quiet"},
            description = "Print nothing unless something fails (for scripts and deployment tools); the exit code says how it went.")
    boolean quiet;

    @Option(names = "--log", paramLabel = "<file>", description = "Also append everything to this file.")
    Path log;

    @Option(names = "--pause", hidden = true, description = "Wait for Enter before exiting (its own window).")
    boolean pause;

    @Mixin
    CatalogOptions catalog = new CatalogOptions();

    private PrintStream out = System.out;

    @Override
    public Integer call() throws Exception {
        PrintStream logStream = null;
        if (log != null) {
            logStream = new PrintStream(new FileOutputStream(log.toFile(), true), true, StandardCharsets.UTF_8);
            logStream.println("--- inicia " + Version.get() + ", "
                    + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        out = new PrintStream(tee(quiet ? OutputStream.nullOutputStream() : System.out, logStream), true,
                StandardCharsets.UTF_8);
        try {
            return install();
        } finally {
            out.flush();
            if (logStream != null) {
                logStream.close();
            }
            if (pause) {
                System.out.println();
                System.out.print("Press Enter to close this window.");
                System.out.flush();
                System.in.read();
                System.out.println();
            }
        }
    }

    private static OutputStream tee(OutputStream a, OutputStream b) {
        if (b == null) {
            return a;
        }
        return new OutputStream() {
            @Override
            public void write(int c) throws IOException {
                a.write(c);
                b.write(c);
            }

            @Override
            public void write(byte[] buf, int off, int len) throws IOException {
                a.write(buf, off, len);
                b.write(buf, off, len);
            }

            @Override
            public void flush() throws IOException {
                a.flush();
                b.flush();
            }
        };
    }

    private int install() throws Exception {
        List<String> wanted = new ArrayList<>(ids);
        if (file != null) {
            wanted.addAll(Selection.fromListFile(file));
        }
        if (wanted.isEmpty()) {
            System.err.println("Nothing to install: name apps (inicia install 7zip vlc) or a list file (-f apps.txt).");
            return 2;
        }
        Catalog cat = catalog.load(wanted);
        List<App> apps = CatalogOptions.resolve(cat, wanted);

        if (!Platform.isWindows() && !dryRun) {
            System.err.println("inicia installs Windows apps; on this system only --dry-run works.");
            return 2;
        }
        List<Step> steps = Planner.plan(apps, Platform.installed(), Platform.arch(), force);
        printPlan(steps, cat.expand(wanted));

        long toRun = steps.stream().filter(s -> s.action().runs()).count();
        if (dryRun || toRun == 0) {
            if (toRun == 0) {
                out.println("Nothing to do.");
            }
            return 0;
        }

        if (!Elevation.isElevated()) {
            if (!Platform.isNativeImage()) {
                System.err.println("Installers need administrator rights: run this from an administrator terminal.");
                return 3;
            }
            return relaunchElevated(wanted);
        }

        Path work = Files.createTempDirectory("inicia-");
        Engine engine = Installation.engine(work, Progress.printing(out));
        out.println();
        Installation.Summary summary = Installation.Summary.of(engine.run(steps));

        out.println();
        out.println("Done: " + summary.counts() + ".");
        for (Engine.Result r : summary.failed()) {
            out.println("  " + r.app().name() + ": " + r.detail());
            if (quiet) {
                System.err.println(r.app().name() + ": " + r.detail());
            }
        }
        if (summary.reboot()) {
            out.println("Restart Windows to finish installing.");
        }
        if (!Installation.cleanUp(work, summary)) {
            out.println("Downloads and logs are kept in " + work);
        }
        return summary.failed().isEmpty() ? 0 : 1;
    }

    private void printPlan(List<Step> steps, List<String> wanted) {
        out.println("inicia " + Version.get() + " · " + steps.size() + (steps.size() == 1 ? " app" : " apps")
                + " · " + Platform.arch().id());
        out.println("Apps from " + catalog.source() + ".");
        Proxies.describe(java.net.URI.create(CatalogOptions.LATEST))
                .ifPresent(p -> out.println("Downloads go through the proxy server at " + p + "."));
        int idWidth = steps.stream().mapToInt(s -> s.app().id().length()).max().orElse(0);
        int nameWidth = steps.stream().mapToInt(s -> title(s.app()).length()).max().orElse(0);
        for (Step s : steps) {
            String what = s.action().label() + s.installed()
                    .filter(v -> s.action() != Step.Action.INSTALL)
                    .map(v -> v.isEmpty() ? "" : " (" + v + " installed)").orElse("");
            if (!wanted.contains(s.app().id())) {
                String neededBy = steps.stream().map(Step::app).filter(a -> a.requires().contains(s.app().id()))
                        .map(App::id).findFirst().orElse("another app");
                what += ", needed by " + neededBy;
            }
            out.printf("  %-" + idWidth + "s  %-" + nameWidth + "s  %s%n", s.app().id(), title(s.app()), what);
            if (s.action().runs()) {
                s.app().note().ifPresent(n -> out.println("  " + " ".repeat(idWidth) + "  note: " + n));
            }
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
        args.addAll(catalog.relaunchArgs());
        if (force) {
            args.add("--force");
        }
        if (log != null) {
            args.add("--log");
            args.add(log.toAbsolutePath().toString());
        }
        if (quiet) {
            // no window to read: the elevated copy runs hidden, and its exit code is ours
            args.add("--quiet");
            int code = Elevation.start(exe, args, true, true, true);
            if (code == Elevation.DECLINED) {
                System.err.println("The administrator prompt was declined; nothing was installed.");
                return 3;
            }
            return code;
        }
        args.add("--pause");
        out.println();
        out.println("Installing needs administrator rights: Windows will ask, and the installs run in a new window.");
        int code = Elevation.start(exe, args, true, false, true);
        if (code == Elevation.DECLINED) {
            out.println("The administrator prompt was declined; nothing was installed.");
            return 3;
        }
        // the elevated window showed everything and waited for Enter; this one needn't
        pause = false;
        return code;
    }
}
