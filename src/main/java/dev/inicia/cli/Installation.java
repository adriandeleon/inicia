package dev.inicia.cli;

import dev.inicia.engine.Downloader;
import dev.inicia.engine.Engine;
import dev.inicia.engine.ProcessRunner;
import dev.inicia.engine.Progress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** What the console and the window share around a run: the engine, its folder, the summary. */
final class Installation {

    record Summary(long installed, long skipped, List<Engine.Result> failed, boolean reboot) {

        static Summary of(List<Engine.Result> results) {
            return new Summary(
                    results.stream().filter(r -> r.status() == Engine.Status.INSTALLED).count(),
                    results.stream().filter(r -> r.status() == Engine.Status.SKIPPED).count(),
                    results.stream().filter(r -> r.status() == Engine.Status.FAILED).toList(),
                    results.stream().anyMatch(Engine.Result::rebootNeeded));
        }

        String counts() {
            return installed + " installed, " + skipped + " left as they were, " + failed.size() + " failed";
        }
    }

    private Installation() {
    }

    static Engine engine(Path work, Progress progress) {
        return new Engine(new Downloader("inicia/" + Version.get()), Platform.signatures(),
                ProcessRunner.system(), Platform.installedApps(), progress, work);
    }

    /** Deletes the run's folder when nothing failed; a failed run keeps it for its logs. */
    static boolean cleanUp(Path work, Summary summary) throws IOException {
        if (!summary.failed().isEmpty()) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(work)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
        return true;
    }
}
