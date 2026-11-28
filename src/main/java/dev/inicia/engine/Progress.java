package dev.inicia.engine;

import dev.inicia.catalog.App;

import java.io.PrintStream;

/** What the engine is doing, for the console or the window to show. */
public interface Progress {

    enum Phase { DOWNLOADING, CHECKING, INSTALLING }

    /** A line of the run's log, as the console prints it. */
    void line(String text);

    /** The app moved to another step of its install. */
    default void phase(App app, Phase phase) {
    }

    /** Bytes downloaded so far; total is -1 when the server didn't say. */
    default void downloaded(App app, long bytes, long total) {
    }

    /**
     * Whether an update waits for the app to be closed, rather than failing: the window waits,
     * since someone is there to close it.
     */
    default boolean waitsForClose() {
        return false;
    }

    /** The app is open, and its update waits for these programs (vlc.exe) to be closed. */
    default void waitingForClose(App app, java.util.List<String> programs) {
    }

    /** An app is done with: installed, skipped or failed. */
    default void finished(Engine.Result result) {
    }

    /** The console: every line printed, nothing else. */
    static Progress printing(PrintStream out) {
        return out::println;
    }
}
