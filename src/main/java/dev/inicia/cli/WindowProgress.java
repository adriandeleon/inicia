package dev.inicia.cli;

import dev.inicia.catalog.App;
import dev.inicia.engine.Engine;
import dev.inicia.engine.Progress;
import dev.inicia.engine.Step;
import dev.inicia.windows.WinFormsUi;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shows the engine's progress in the window: each app's status, and one bar for the whole run. */
final class WindowProgress implements Progress {

    // how far through an app each step is, for the overall bar: the download is most of the wait
    private static final double DOWNLOADED = 0.7;
    private static final double CHECKED = 0.75;

    private final WinFormsUi ui;
    private final List<App> running;
    private final Map<String, Double> done = new HashMap<>();
    private final Map<String, Long> shown = new HashMap<>();
    private String activity = "Getting ready…";

    WindowProgress(WinFormsUi ui, List<Step> steps) {
        this.ui = ui;
        this.running = steps.stream().filter(s -> s.action().runs()).map(Step::app).toList();
    }

    @Override
    public void line(String text) {
        ui.send(WinFormsUi.event("log").put("text", text));
    }

    @Override
    public void phase(App app, Phase phase) {
        String what = switch (phase) {
            case DOWNLOADING -> "Downloading";
            case CHECKING -> "Checking the download";
            case INSTALLING -> "Installing";
        };
        done.put(app.id(), switch (phase) {
            case DOWNLOADING -> 0.0;
            case CHECKING -> DOWNLOADED;
            case INSTALLING -> CHECKED;
        });
        app(app, what + "…", "working");
        int index = running.indexOf(app) + 1;
        activity = what + " " + app.name() + (running.size() > 1 ? " (" + index + " of " + running.size() + ")" : "") + "…";
        overall();
    }

    @Override
    public boolean waitsForClose() {
        return true;
    }

    @Override
    public void waitingForClose(App app, List<String> programs) {
        app(app, "Close " + app.name() + " to update it (" + String.join(", ", programs) + ")", "note");
        activity = "Waiting for " + app.name() + " to be closed…";
        overall();
    }

    @Override
    public void downloaded(App app, long bytes, long total) {
        if (total > 0) {
            long percent = bytes * 100 / total;
            if (shown.getOrDefault(app.id(), -1L) == percent) {
                return;
            }
            shown.put(app.id(), percent);
            done.put(app.id(), DOWNLOADED * bytes / total);
            app(app, "Downloading " + percent + "%", "working");
        } else {
            long mb = bytes / (1024 * 1024);
            if (shown.getOrDefault(app.id(), -1L) == mb) {
                return;
            }
            shown.put(app.id(), mb);
            app(app, "Downloading " + mb + " MB", "working");
        }
        overall();
    }

    @Override
    public void finished(Engine.Result result) {
        App app = result.app();
        String state = switch (result.status()) {
            case INSTALLED -> "ok";
            case SKIPPED -> "skip";
            case FAILED -> "fail";
        };
        String status = switch (result.status()) {
            case FAILED -> "Failed: " + result.detail();
            // the catalog's version, as the window lists it: Windows' own number for it can differ
            // (PuTTY 0.85 is 0.85.0.0 there, Python 3.15.2 is 3.15.2150.0); the log has that one
            case INSTALLED -> (app.version().map(v -> "Installed " + v).orElse(capitalize(result.detail())))
                    + (result.rebootNeeded() ? " · restart to finish" : "");
            case SKIPPED -> result.detail().equals("cancelled") ? "Cancelled"
                    : result.detail().startsWith("not updated") ? capitalize(result.detail())
                    : app.version().map(v -> "Up to date (" + v + ")").orElse("Already installed");
        };
        app(app, status, state);
        if (running.contains(app)) {
            done.put(app.id(), 1.0);
            overall();
        }
    }

    private void app(App app, String status, String state) {
        ui.send(WinFormsUi.event("app").put("id", app.id()).put("status", status).put("state", state));
    }

    private void overall() {
        double sum = running.stream().mapToDouble(a -> done.getOrDefault(a.id(), 0.0)).sum();
        int value = running.isEmpty() ? 1000 : (int) Math.round(1000 * sum / running.size());
        ui.send(WinFormsUi.event("overall").put("value", value).put("text", activity));
    }

    /** The status of an app the plan leaves alone, for its row before anything runs. */
    static String leftAlone(Step s) {
        return switch (s.action()) {
            case UP_TO_DATE -> s.app().version().map(v -> "Up to date (" + v + ")").orElse("Up to date");
            case PRESENT -> "Already installed";
            default -> capitalize(s.action().label());
        };
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
