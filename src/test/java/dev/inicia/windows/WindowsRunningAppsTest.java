package dev.inicia.windows;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsRunningAppsTest {

    private static final Map<String, String> ENV = Map.of(
            "ProgramFiles", "C:\\Program Files",
            "ProgramFiles(x86)", "C:\\Program Files (x86)",
            "LOCALAPPDATA", "C:\\Users\\ada\\AppData\\Local",
            "APPDATA", "C:\\Users\\ada\\AppData\\Roaming",
            "USERPROFILE", "C:\\Users\\ada",
            "SystemRoot", "C:\\Windows");

    @Test
    void onlyAnAppsOwnFolderIsSearched() {
        assertThat(WindowsRunningApps.specific("C:\\Program Files\\VideoLAN\\VLC", ENV)).isTrue();
        assertThat(WindowsRunningApps.specific("C:\\Program Files\\Git\\", ENV)).isTrue();
        assertThat(WindowsRunningApps.specific("C:\\Python315", ENV)).isTrue();
        assertThat(WindowsRunningApps.specific("\"C:\\Program Files\\Yaak\"", ENV)).isTrue();
        assertThat(WindowsRunningApps.specific("C:\\Users\\ada\\AppData\\Local\\Programs\\Microsoft VS Code", ENV)).isTrue();

        assertThat(WindowsRunningApps.specific("C:\\Program Files", ENV)).isFalse();
        assertThat(WindowsRunningApps.specific("c:\\program files (x86)\\", ENV)).isFalse();
        assertThat(WindowsRunningApps.specific("C:\\Users\\ada\\AppData\\Local\\Programs", ENV)).isFalse();
        assertThat(WindowsRunningApps.specific("C:\\Windows\\Installer\\{1234}", ENV)).isFalse();
        assertThat(WindowsRunningApps.specific("C:\\", ENV)).isFalse();
        assertThat(WindowsRunningApps.specific("relative\\dir", ENV)).isFalse();
    }
}
