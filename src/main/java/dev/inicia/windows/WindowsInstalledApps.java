package dev.inicia.windows;

import dev.inicia.engine.InstalledApp;
import dev.inicia.engine.InstalledApps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What Settings > Apps lists: the Uninstall keys of 64-bit and 32-bit programs for the machine,
 * and the current user's, then the current user's MSIX packages (by package name: Claude,
 * OpenAI.Codex). Entries marked SystemComponent are hidden there and skipped here too.
 */
public final class WindowsInstalledApps implements InstalledApps {

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $keys = 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                    'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                    'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'
            Get-ItemProperty -Path $keys |
              Where-Object { $_.DisplayName -and $_.SystemComponent -ne 1 } |
              ForEach-Object {
                # some installers (Yaak's) write it in quotes
                $dir = ([string]$_.InstallLocation).Trim().Trim('"')
                if (-not $dir -and $_.DisplayIcon) {
                  # "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe,0": the folder of the exe its icon comes from
                  $icon = ([string]$_.DisplayIcon).Split(',')[0].Trim().Trim('"')
                  if ($icon -match '\\.exe$') { $dir = Split-Path -Parent $icon }
                }
                [string]$_.DisplayName + "`t" + [string]$_.DisplayVersion + "`t" + $dir
              }
            Get-AppxPackage | Where-Object { -not $_.IsFramework } |
              ForEach-Object { [string]$_.Name + "`t" + [string]$_.Version + "`t" + [string]$_.InstallLocation }
            # an Uninstall entry that can't be read is skipped (SilentlyContinue), but it still
            # leaves $? false, and PowerShell would exit with 1: the list above is complete anyway
            exit 0
            """;

    @Override
    public List<InstalledApp> list() throws IOException, InterruptedException {
        return parse(PowerShell.run(SCRIPT, Map.of()));
    }

    /** One "name TAB version TAB folder" per line; the folder, or both, may be missing. */
    static List<InstalledApp> parse(String output) {
        List<InstalledApp> apps = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            String name = parts[0].strip();
            String version = parts.length > 1 ? parts[1].strip() : "";
            String location = parts.length > 2 ? parts[2].strip() : "";
            if (!name.isEmpty()) {
                apps.add(new InstalledApp(name, version, location));
            }
        }
        return apps;
    }
}
