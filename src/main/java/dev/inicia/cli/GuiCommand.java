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
            List<Engine.Result> results = run(ui, work, steps);
            // Retry failed runs the failed apps again, in the same window, as often as asked
            for (String answer; !(answer = ui.next()).equals(WinFormsUi.CLOSED); ) {
                if (!answer.equals("retry")) {
                    continue;
                }
                List<App> failed = results.stream().filter(r -> r.status() == Engine.Status.FAILED)
                        .map(Engine.Result::app).toList();
                List<Step> again = Planner.plan(failed, Platform.installed(), Platform.arch(), force);
                ui.send(retryEvent(again));
                List<Engine.Result> retried = run(ui, work, again);
                results = merge(results, retried);
            }
            Installation.Summary summary = Installation.Summary.of(results);
            Installation.cleanUp(work, summary);
            return summary.failed().isEmpty() ? 0 : 1;
        }
    }

    /** Runs the steps with the window showing them, then shows how it went; the window stays open. */
    private static List<Engine.Result> run(WinFormsUi ui, Path work, List<Step> steps) throws InterruptedException {
        Engine engine = Installation.engine(work, new WindowProgress(ui, steps));
        Thread answers = new Thread(() -> {
            // while the engine runs, the window's only answer is cancel; anything else waits
            try {
                for (String a; (a = ui.next()) != null; ) {
                    if (a.equals("cancel")) {
                        engine.cancel();
                    } else {
                        ui.putBack(a);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "inicia-answers");
        answers.setDaemon(true);
        answers.start();
        List<Engine.Result> results = engine.run(steps);
        answers.interrupt();
        answers.join(1000);
        ui.send(doneEvent(Installation.Summary.of(results), work));
        return results;
    }

    private static List<Engine.Result> merge(List<Engine.Result> before, List<Engine.Result> retried) {
        List<Engine.Result> merged = new ArrayList<>(before);
        for (Engine.Result r : retried) {
            merged.replaceAll(old -> old.app().equals(r.app()) ? r : old);
        }
        return merged;
    }

    private static ObjectNode doneEvent(Installation.Summary s, Path work) {
        List<String> counts = new ArrayList<>();
        if (s.installed() > 0) {
            counts.add(s.installed() + " installed");
        }
        if (s.skipped() > 0) {
            counts.add(s.skipped() + " already up to date");
        }
        if (!s.failed().isEmpty()) {
            counts.add(s.failed().size() + " failed");
        }
        boolean ok = s.failed().isEmpty();
        String text = ok
                ? (s.reboot() ? "Restart Windows to finish installing." : "Everything you picked is installed and up to date.")
                : "Retry failed tries them again. Show details says what went wrong, and the log folder "
                + "keeps the downloads and installer logs." + (s.reboot() ? " Restart Windows to finish installing." : "");
        ObjectNode event = WinFormsUi.event("done")
                .put("title", (ok ? "Done: " : "Finished: ") + String.join(" · ", counts))
                .put("text", text).put("ok", ok);
        if (!ok) {
            event.put("logs", work.toString());
        }
        return event;
    }

    private static ObjectNode retryEvent(List<Step> steps) {
        ObjectNode event = WinFormsUi.event("retry")
                .put("title", "Trying " + steps.size() + (steps.size() == 1 ? " app" : " apps") + " again");
        ArrayNode ids = event.putArray("ids");
        steps.forEach(s -> ids.add(s.app().id()));
        return event;
    }

    static Path self() {
        return Path.of(ProcessHandle.current().info().command().orElseThrow());
    }

    private static ObjectNode pickEvent(Catalog cat) throws java.io.IOException, InterruptedException {
        ObjectNode event = WinFormsUi.event("pick");
        ArrayNode apps = event.putArray("apps");
        Map<String, List<String>> suites = new LinkedHashMap<>();
        // what's installed already, so the picker can say "Installed" or "Update available"
        Map<String, Step> state = new java.util.HashMap<>();
        for (Step s : Planner.plan(cat.apps(), Platform.installed(), Platform.arch(), false)) {
            state.put(s.app().id(), s);
        }
        for (App a : cat.apps()) {
            ObjectNode n = apps.addObject().put("id", a.id()).put("name", a.name()).put("category", a.category())
                    .put("description", a.description());
            a.version().ifPresent(v -> n.put("version", v));
            a.note().ifPresent(v -> n.put("note", v));
            a.license().ifPresent(v -> n.put("license", v));
            a.installerFor(Platform.arch()).flatMap(i -> i.size().isPresent()
                    ? java.util.Optional.of(i.size().getAsLong()) : java.util.Optional.empty())
                    .ifPresent(size -> n.put("size", size));
            Step step = state.get(a.id());
            n.put("installed", switch (step.action()) {
                case UP_TO_DATE, PRESENT -> "Installed";
                case UPGRADE -> "Update available";
                case NO_INSTALLER -> "Not for this PC";
                default -> "";
            });
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
            String status = s.action().runs() ? "Waiting" : WindowProgress.leftAlone(s);
            apps.addObject().put("id", s.app().id()).put("name", InstallCommand.title(s.app())).put("status", status)
                    .put("state", s.action().runs() ? "waiting" : "skip");
        }
        return event;
    }
}
