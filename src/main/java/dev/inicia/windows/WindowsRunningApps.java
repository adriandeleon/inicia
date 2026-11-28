package dev.inicia.windows;

import dev.inicia.engine.RunningApps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The running programs whose exe is inside an app's folder. Folders that hold far more than one
 * app (Program Files, AppData, Windows) are never searched: an app whose Uninstall entry points
 * at one would otherwise seem open whenever anything is.
 */
public final class WindowsRunningApps implements RunningApps {

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $dirs = $env:INICIA_DIRS -split "`n" | Where-Object { $_ } | ForEach-Object { $_.TrimEnd('\\') + '\\' }
            Get-Process | ForEach-Object {
              $path = $_.Path
              if ($path) {
                foreach ($d in $dirs) {
                  if ($path.StartsWith($d, [StringComparison]::OrdinalIgnoreCase)) { Split-Path -Leaf $path; break }
                }
              }
            } | Sort-Object -Unique
            exit 0
            """;

    @Override
    public List<String> runningIn(List<String> folders) throws IOException, InterruptedException {
        List<String> dirs = folders.stream().map(f -> f.strip().replaceAll("^\"|\"$", ""))
                .filter(WindowsRunningApps::specific).toList();
        if (dirs.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : PowerShell.run(SCRIPT, Map.of("INICIA_DIRS", String.join("\n", dirs))).split("\\R")) {
            if (!line.isBlank()) {
                names.add(line.strip());
            }
        }
        return names;
    }

    /** Whether a folder is one app's own, rather than a place many apps live in. */
    static boolean specific(String folder) {
        return specific(folder, System.getenv());
    }

    static boolean specific(String folder, Map<String, String> env) {
        // plain string work, not Path: this is a Windows path whichever machine the tests run on
        String d = key(folder);
        if (!d.matches("[a-z]:\\\\[^\\\\].*") || d.contains("\\..")) {
            return false;   // not absolute, or a whole drive
        }
        List<String> shared = new ArrayList<>();
        for (String var : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData",
                "LOCALAPPDATA", "APPDATA", "USERPROFILE", "SystemRoot")) {
            String v = env.get(var);
            if (v != null && !v.isBlank()) {
                shared.add(key(v));
            }
        }
        String local = env.get("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            shared.add(key(local + "\\Programs"));
            shared.add(key(local + "\\Microsoft"));
        }
        if (shared.contains(d)) {
            return false;
        }
        String root = env.getOrDefault("SystemRoot", "C:\\Windows");
        // Windows' own folders (C:\Windows\Installer holds the icons of MSI-installed apps)
        return !d.startsWith(key(root) + "\\");
    }

    private static String key(String path) {
        String p = path.strip().replaceAll("^\"|\"$", "").strip().replace('/', '\\');
        while (p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.toLowerCase(Locale.ROOT);
    }
}
