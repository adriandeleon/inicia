package dev.inicia.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs an installer and waits for it. */
public interface ProcessRunner {

    /** @return the exit code */
    int run(List<String> command, Duration timeout) throws IOException, InterruptedException;

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
