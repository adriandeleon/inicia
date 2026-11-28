package dev.inicia.windows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Whether inicia runs as administrator, and starting it again as one (the UAC prompt). */
public final class Elevation {

    private Elevation() {
    }

    /** True when the process token carries the High (or System) mandatory level. */
    public static boolean isElevated() {
        try {
            Process p = new ProcessBuilder(PowerShell.system32("whoami.exe").toString(), "/groups")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return out.contains("S-1-16-12288") || out.contains("S-1-16-16384");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** What {@link #start} returns when the UAC prompt was declined (ERROR_CANCELLED). */
    public static final int DECLINED = 1223;

    /**
     * Starts {@code exe} with {@code args}.
     *
     * @param elevate as administrator: Windows shows the UAC prompt
     * @param hidden  with its console window hidden (the window inicia shows itself, or none)
     * @param wait    wait for it to end
     * @return its exit code when waiting, else 0; {@link #DECLINED} when the UAC prompt was declined
     */
    public static int start(Path exe, List<String> args, boolean elevate, boolean hidden, boolean wait)
            throws IOException, InterruptedException {
        String script = """
                $a = @{ FilePath = $env:INICIA_EXE; ArgumentList = $env:INICIA_ARGS; PassThru = $true }
                if ($env:INICIA_ELEVATE -eq '1') { $a.Verb = 'RunAs' }
                if ($env:INICIA_HIDDEN -eq '1') { $a.WindowStyle = 'Hidden' }
                if ($env:INICIA_WAIT -eq '1') { $a.Wait = $true }
                try {
                  $p = Start-Process @a
                  if ($env:INICIA_WAIT -eq '1') { $p.ExitCode } else { 0 }
                } catch { 1223 }
                """;
        String out = PowerShell.run(script, Map.of(
                "INICIA_EXE", exe.toString(),
                "INICIA_ARGS", commandLine(args),
                "INICIA_ELEVATE", elevate ? "1" : "0",
                "INICIA_HIDDEN", hidden ? "1" : "0",
                "INICIA_WAIT", wait ? "1" : "0"));
        try {
            return Integer.parseInt(out.strip());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Joins arguments into one Windows command line that CommandLineToArgvW (and so the JVM's
     * and native image's argument parsing) splits back into the same arguments.
     */
    public static String commandLine(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) {
                sb.append(arg);
                continue;
            }
            sb.append('"');
            int backslashes = 0;
            for (char c : arg.toCharArray()) {
                if (c == '\\') {
                    backslashes++;
                } else if (c == '"') {
                    sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                    backslashes = 0;
                } else {
                    sb.append("\\".repeat(backslashes)).append(c);
                    backslashes = 0;
                }
            }
            sb.append("\\".repeat(backslashes * 2)).append('"');
        }
        return sb.toString();
    }
}
