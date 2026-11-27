package dev.inicia.cli;

import dev.inicia.engine.Selection;
import dev.inicia.windows.Elevation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "inicia",
        mixinStandardHelpOptions = true,
        versionProvider = Version.class,
        description = {
                "Installs a chosen set of Windows apps in one go: silently, checked against the catalog's",
                "SHA-256 or the publisher's signature, with no toolbars or other bundled extras.",
                "",
                "Run with no command (a double-click), it opens its window: the apps an",
                "inicia_<id>_<id>.exe name selects, with a progress bar, or a picker for a plain",
                "inicia.exe. The commands below are the command line, for scripts and administrators."},
        subcommands = {
                InstallCommand.class,
                ListCommand.class,
                StatusCommand.class,
                HashCommand.class,
                CheckCommand.class,
                GuiCommand.class,
                CommandLine.HelpCommand.class})
public final class Main implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--cli", description = "Install the apps the exe's name selects here in the console, not in the window.")
    boolean cli;

    @Option(names = "--silent",
            description = "Install the apps the exe's name selects with no window and no output; the exit code says how it went.")
    boolean silent;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String... args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((e, cmd, parsed) -> {
            cmd.getErr().println("inicia: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        });
        return cli.execute(args);
    }

    /** No command: the window, or with --cli or --silent the console install of the exe's selection. */
    @Override
    public Integer call() throws Exception {
        List<String> ids = Selection.fromThisExe();
        if (silent || cli || !Platform.isWindows() || !Platform.isNativeImage()) {
            if (ids.isEmpty()) {
                if (silent) {
                    System.err.println("inicia: --silent installs the apps the exe's name selects "
                            + "(inicia_7zip_vlc.exe); for others, use inicia install <ids> --quiet");
                    return 2;
                }
                spec.commandLine().usage(spec.commandLine().getOut());
                return 0;
            }
            InstallCommand install = new InstallCommand();
            install.ids = ids;
            install.quiet = silent;
            // a double-clicked console closes when inicia ends: keep it open to be read
            install.pause = !silent && !cli;
            return install.call();
        }

        // The window. This console exists only because inicia is a console program: start a copy
        // with its console hidden, elevated straight away when there are apps to install (the
        // picker runs without, and asks once it knows there's something to do), and end here.
        List<String> args = new ArrayList<>(List.of("gui"));
        args.addAll(ids);
        boolean elevate = !ids.isEmpty() && !Elevation.isElevated();
        int code = Elevation.start(GuiCommand.self(), args, elevate, true, false);
        if (code == Elevation.DECLINED) {
            Messages.show("inicia needs administrator rights to install apps, and Windows wasn't given them. "
                    + "Nothing was installed.");
            return 3;
        }
        return code;
    }
}
