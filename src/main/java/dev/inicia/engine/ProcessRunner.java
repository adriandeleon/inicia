package dev.inicia.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an installer and waits for it. */
public interface ProcessRunner {

    /** @return the exit code */
    int run(List<String> command, Duration timeout) throws IOException, InterruptedException;

    /**
     * Runs it as the signed-in user, without administrator rights, for installers that refuse to
     * run elevated. Where there's no such user to run it as, it runs the usual way.
     */
    default int runAsUser(List<String> command, Duration timeout) throws IOException, InterruptedException {
        return run(command, timeout);
    }

    /** The real thing: no window output is kept, since silent installers print nothing useful. */
    static ProcessRunner system() {
        return (command, timeout) -> {
            Process p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the installer was still running after " + timeout.toMinutes() + " minutes");
            }
            return p.exitValue();
        };
    }
}
