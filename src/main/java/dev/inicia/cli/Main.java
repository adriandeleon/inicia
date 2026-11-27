package dev.inicia.cli;

import dev.inicia.engine.Selection;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "inicia",
        mixinStandardHelpOptions = true,
        versionProvider = Version.class,
        description = {
                "Installs a chosen set of Windows apps in one go: silently, checked against the catalog's",
                "SHA-256 or the publisher's signature, with no toolbars or other bundled extras.",
                "",
                "Named inicia_<id>_<id>.exe (as the picker page names its downloads), it installs",
                "those apps when run with no arguments."},
        subcommands = {
                InstallCommand.class,
                ListCommand.class,
                StatusCommand.class,
                HashCommand.class,
                CheckCommand.class,
                CommandLine.HelpCommand.class})
public final class Main implements Callable<Integer> {

    @Spec
    CommandSpec spec;

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

    /** No command: install what the exe's name selects, or show the help. */
    @Override
    public Integer call() throws Exception {
        List<String> ids = Selection.fromThisExe();
        if (ids.isEmpty()) {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 0;
        }
        InstallCommand install = new InstallCommand();
        install.ids = ids;
        install.pause = true;
        install.fromFileName = true;
        return install.call();
    }
}
