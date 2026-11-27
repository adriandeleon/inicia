package dev.inicia.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.inicia.catalog.App;
import dev.inicia.catalog.Catalog;
import dev.inicia.engine.Engine;
import dev.inicia.engine.Planner;
import dev.inicia.engine.Step;
import dev.inicia.windows.Elevation;
import dev.inicia.windows.WinFormsUi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The window: a picker when no apps are named, then the installs with a progress bar. A
 * double-click starts this with its console hidden; it asks for administrator rights itself.
 */
@Command(name = "gui", hidden = true, description = "Opens inicia's window (what a double-click does).")
final class GuiCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<id>", arity = "0..*")
    List<String> ids = new ArrayList<>();

    @Option(names = "--force")
    boolean force;

    @Mixin
    CatalogOptions catalog = new CatalogOptions();

    @Override
    public Integer call() throws Exception {
        try {
            return show();
        } catch (Exception e) {
            Messages.show("inicia couldn't install the apps: " + e.getMessage());
            return 1;
        }
    }

    private int show() throws Exception {
        Catalog cat = catalog.load();
        List<String> wanted = ids;
        if (wanted.isEmpty()) {
            try (WinFormsUi ui = WinFormsUi.open()) {
                ui.send(pickEvent(cat));
                String answer = ui.next();
                if (!answer.startsWith("install ")) {
                    return 0;
                }
                wanted = Arrays.asList(answer.substring("install ".length()).strip().split("\\s+"));
            }
        }

        if (!Elevation.isElevated()) {
            List<String> args = new ArrayList<>(List.of("gui"));
            args.addAll(wanted);
            for (String c : catalog.absolute()) {
                args.add("--catalog");
                args.add(c);
            }
            if (force) {
                args.add("--force");
            }
            int code = Elevation.start(self(), args, true, true, false);
            if (code == Elevation.DECLINED) {
                Messages.show("inicia needs administrator rights to install apps, and Windows wasn't given them. "
                        + "Nothing was installed.");
                return 3;
            }
            return code;
        }

        List<App> apps = CatalogOptions.resolve(cat, wanted);
        List<Step> steps = Planner.plan(apps, Platform.installed(), Platform.arch(), force);
        long toRun = steps.stream().filter(s -> s.action().runs()).count();

        try (WinFormsUi ui = WinFormsUi.open()) {
            ui.send(planEvent(steps, toRun));
            if (toRun == 0) {
                ui.send(WinFormsUi.event("done").put("title", "Nothing to do")
                        .put("text", (steps.size() == 1 ? "It's" : "All " + steps.size() + " apps are")
                                + " already installed and up to date.").put("ok", true));
                ui.waitClosed();
                return 0;
            }

            Path work = Files.createTempDirectory("inicia-");
            Engine engine = Installation.engine(work, new WindowProgress(ui, steps));
            Thread answers = new Thread(() -> {
                try {
                    for (String a; !(a = ui.next()).equals(WinFormsUi.CLOSED); ) {
                        if (a.equals("cancel")) {
                            engine.cancel();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "inicia-answers");
            answers.setDaemon(true);
            answers.start();

            Installation.Summary summary = Installation.Summary.of(engine.run(steps));
            boolean kept = !Installation.cleanUp(work, summary);
            String text = WindowProgress.capitalize(summary.counts()) + "."
                    + (summary.reboot() ? " Restart Windows to finish installing." : "")
                    + (kept ? " Downloads and logs are kept in " + work + "." : "");
            ui.send(WinFormsUi.event("done")
                    .put("title", summary.failed().isEmpty() ? "Done" : "Finished, with problems")
                    .put("text", text).put("ok", summary.failed().isEmpty()));
            ui.waitClosed();
            return summary.failed().isEmpty() ? 0 : 1;
        }
    }

    static Path self() {
        return Path.of(ProcessHandle.current().info().command().orElseThrow());
    }

    private static ObjectNode pickEvent(Catalog cat) {
        ObjectNode event = WinFormsUi.event("pick");
        ArrayNode apps = event.putArray("apps");
        Map<String, List<String>> suites = new LinkedHashMap<>();
        for (App a : cat.apps()) {
            ObjectNode n = apps.addObject().put("id", a.id()).put("name", a.name()).put("category", a.category())
                    .put("description", a.description());
            a.version().ifPresent(v -> n.put("version", v));
            a.note().ifPresent(v -> n.put("note", v));
            a.suite().ifPresent(s -> {
                n.put("suite", s);
                suites.computeIfAbsent(s, k -> new ArrayList<>()).add(a.id());
            });
        }
        ArrayNode suiteList = event.putArray("suites");
        suites.forEach((name, ids) -> {
            ObjectNode s = suiteList.addObject().put("name", name);
            ids.forEach(s.putArray("ids")::add);
        });
        return event;
    }

    private static ObjectNode planEvent(List<Step> steps, long toRun) {
        ObjectNode event = WinFormsUi.event("plan")
                .put("title", toRun == 0 ? "Checking your apps"
                        : "Installing " + toRun + (toRun == 1 ? " app" : " apps"));
        ArrayNode apps = event.putArray("apps");
        for (Step s : steps) {
            String status = s.action().runs() ? "Waiting"
                    : WindowProgress.capitalize(s.action().label())
                    + s.installed().filter(v -> !v.isEmpty()).map(v -> " (" + v + ")").orElse("");
            apps.addObject().put("id", s.app().id()).put("name", InstallCommand.title(s.app())).put("status", status);
        }
        return event;
    }
}
