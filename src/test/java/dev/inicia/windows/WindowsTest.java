package dev.inicia.windows;

import dev.inicia.engine.InstalledApp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsTest {

    @Test
    void parsesInstalledList() {
        String out = "7-Zip 26.06 (x64)\t26.06\r\nNotepad++ (64-bit x64)\t8.9.9.1\r\nNo Version App\t\r\n\r\nÉditeur\t1.0\n";
        assertThat(WindowsInstalledApps.parse(out)).containsExactly(
                new InstalledApp("7-Zip 26.06 (x64)", "26.06"),
                new InstalledApp("Notepad++ (64-bit x64)", "8.9.9.1"),
                new InstalledApp("No Version App", ""),
                new InstalledApp("Éditeur", "1.0"));
    }

    @Test
    void commandLineRoundTripsThroughWindowsRules() {
        assertThat(Elevation.commandLine(List.of("install", "7zip", "--pause"))).isEqualTo("install 7zip --pause");
        assertThat(Elevation.commandLine(List.of("--catalog", "C:\\My Files\\apps.json")))
                .isEqualTo("--catalog \"C:\\My Files\\apps.json\"");
        // a trailing backslash before the closing quote is doubled, so it doesn't escape the quote
        assertThat(Elevation.commandLine(List.of("C:\\My Dir\\"))).isEqualTo("\"C:\\My Dir\\\\\"");
        assertThat(Elevation.commandLine(List.of("say \"hi\""))).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(Elevation.commandLine(List.of(""))).isEqualTo("\"\"");
    }
}
