package dev.inicia.engine;

import dev.inicia.catalog.App;
import dev.inicia.catalog.Installer;
import dev.inicia.catalog.InstallerType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Downloads, checks and runs the installers a plan calls for, one at a time. */
public final class Engine {

    public enum Status { INSTALLED, SKIPPED, FAILED }

    public record Result(App app, Status status, String detail, boolean rebootNeeded) {
    }

    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(30);

    private final Downloader downloader;
    private final SignatureCheck signatures;
    private final ProcessRunner runner;
    private final InstalledApps installedApps;
    private final Progress progress;
    private final Path workDir;
    private volatile boolean cancelled;

    public Engine(Downloader downloader, SignatureCheck signatures, ProcessRunner runner,
                  InstalledApps installedApps, Progress progress, Path workDir) {
        this.downloader = downloader;
        this.signatures = signatures;
        this.runner = runner;
        this.installedApps = installedApps;
        this.progress = progress;
        this.workDir = workDir;
    }

    /** Stops before the next app; an installer already running is left to finish. */
    public void cancel() {
        cancelled = true;
    }

    public List<Result> run(List<Step> steps) throws InterruptedException {
        List<Result> results = new ArrayList<>();
        for (Step step : steps) {
            Result result;
            if (!step.action().runs()) {
                result = new Result(step.app(), Status.SKIPPED, step.action().label(), false);
            } else if (cancelled) {
                result = new Result(step.app(), Status.SKIPPED, "cancelled", false);
            } else {
                result = runOne(step);
            }
            results.add(result);
            progress.finished(result);
        }
        return results;
    }

    private Result runOne(Step step) throws InterruptedException {
        App app = step.app();
        Installer installer = step.installer().orElseThrow();
        String label = app.name() + app.version().map(v -> " " + v).orElse("");
        Path dir = workDir.resolve(app.id());
        Path file = dir.resolve(installer.fileName(app.id()));
        try {
            Files.createDirectories(dir);
            progress.phase(app, Progress.Phase.DOWNLOADING);
            progress.line(label + ": downloading from " + installer.url().getHost());
            Downloader.Download download = downloader.fetch(installer.url(), file,
                    (bytes, total) -> progress.downloaded(app, bytes, total > 0 ? total : installer.size().orElse(-1)));
            progress.line("  " + size(download.size()) + " from " + download.from().getHost());

            progress.phase(app, Progress.Phase.CHECKING);

            Optional<String> problem = verify(installer, download);
            if (problem.isPresent()) {
                Files.deleteIfExists(file);
                return failed(app, problem.get());
            }

            progress.phase(app, Progress.Phase.INSTALLING);
            progress.line("  installing (" + installer.type().id() + ", silent"
                    + (installer.asUser() ? ", as the signed-in user" : "") + ")");
            Path msiLog = installer.type() == InstallerType.MSI ? dir.resolve("msiexec.log") : null;
            List<String> command = InstallerCommand.build(installer, file, msiLog);
            int code = installer.asUser() ? runner.runAsUser(command, INSTALL_TIMEOUT) : runner.run(command, INSTALL_TIMEOUT);
            InstallerCommand.Outcome outcome = InstallerCommand.outcome(code);
            if (outcome == InstallerCommand.Outcome.FAILED) {
                String why = InstallerCommand.explain(code).map(s -> ": " + s).orElse("");
                String log = msiLog != null && Files.exists(msiLog) ? " (log: " + msiLog + ")" : "";
                return failed(app, "the installer exited with code " + code + why + log);
            }
            Files.deleteIfExists(file);
            boolean reboot = outcome == InstallerCommand.Outcome.OK_REBOOT;

            Optional<String> now = Planner.installedVersion(app, installedApps.list());
            String detail;
            if (now.isPresent()) {
                detail = now.get().isEmpty() ? "installed" : "installed " + now.get();
            } else {
                detail = "installer finished, but Windows' list of programs doesn't show " + app.name();
            }
            progress.line("  " + detail + (reboot ? " (restart Windows to finish)" : ""));
            return new Result(app, Status.INSTALLED, detail, reboot);
        } catch (IOException e) {
            return failed(app, Downloader.describe(e));
        }
    }

    private Optional<String> verify(Installer installer, Downloader.Download download)
            throws IOException, InterruptedException {
        if (installer.sha256().isPresent()) {
            if (!installer.sha256().get().equals(download.sha256())) {
                return Optional.of("SHA-256 mismatch: the catalog has " + installer.sha256().get()
                        + ", the download is " + download.sha256());
            }
            progress.line("  SHA-256 matches the catalog");
        }
        if (installer.signer().isPresent()) {
            SignatureCheck.Result sig = signatures.check(download.file());
            if (!sig.valid()) {
                return Optional.of("Authenticode signature isn't valid (" + sig.status() + ")");
            }
            if (!sig.signedBy(installer.signer().get())) {
                return Optional.of("signed by \"" + sig.subject() + "\", not " + installer.signer().get());
            }
            progress.line("  signed by " + installer.signer().get());
        }
        return Optional.empty();
    }

    private Result failed(App app, String why) {
        progress.line("  FAILED: " + why);
        return new Result(app, Status.FAILED, why, false);
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
