package dev.inicia.windows;

import dev.inicia.engine.InstalledApp;
import dev.inicia.engine.InstalledApps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What Settings > Apps lists: the Uninstall keys of 64-bit and 32-bit programs for the machine,
 * and the current user's. Entries marked SystemComponent are hidden there and skipped here too.
 */
public final class WindowsInstalledApps implements InstalledApps {

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $keys = 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                    'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                    'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'
            Get-ItemProperty -Path $keys |
              Where-Object { $_.DisplayName -and $_.SystemComponent -ne 1 } |
              ForEach-Object { [string]$_.DisplayName + "`t" + [string]$_.DisplayVersion }
            """;

    @Override
    public List<InstalledApp> list() throws IOException, InterruptedException {
        return parse(PowerShell.run(SCRIPT, Map.of()));
    }

    /** One "name TAB version" per line. */
    static List<InstalledApp> parse(String output) {
        List<InstalledApp> apps = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String name = (tab < 0 ? line : line.substring(0, tab)).strip();
            String version = tab < 0 ? "" : line.substring(tab + 1).strip();
            if (!name.isEmpty()) {
                apps.add(new InstalledApp(name, version));
            }
        }
        return apps;
    }
}
