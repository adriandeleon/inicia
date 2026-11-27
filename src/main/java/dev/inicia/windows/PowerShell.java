package dev.inicia.windows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a Windows PowerShell script and returns what it prints. Always the System32 copy, found by
 * full path: inicia runs elevated, so a powershell.exe earlier on the PATH must never win. The
 * script travels base64-encoded and its inputs as environment variables, so nothing needs quoting.
 */
final class PowerShell {

    private PowerShell() {
    }

    static Path exe() {
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    }

    static Path system32(String exe) {
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return Path.of(root, "System32", exe);
    }

    static String run(String script, Map<String, String> env) throws IOException, InterruptedException {
        // UTF-8 out, whatever the console's code page: display names aren't all ASCII
        String full = "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
                + "$ProgressPreference = 'SilentlyContinue'\n" + script;
        String encoded = Base64.getEncoder().encodeToString(full.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder(exe().toString(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")));
        pb.environment().putAll(env);
        Process p = pb.start();
        byte[] outBytes = p.getInputStream().readAllBytes();
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("PowerShell didn't finish");
        }
        if (p.exitValue() != 0) {
            throw new IOException("PowerShell exited with code " + p.exitValue());
        }
        String out = new String(outBytes, StandardCharsets.UTF_8);
        return out.startsWith("\uFEFF") ? out.substring(1) : out;
    }
}
