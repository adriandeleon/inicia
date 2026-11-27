package dev.inicia.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionTest {

    @Test
    void idsFromTheExeName() {
        assertThat(Selection.fromFileName("inicia_7zip_vlc_notepad-plus-plus.exe"))
                .containsExactly("7zip", "vlc", "notepad-plus-plus");
        // a browser that already has the file, and Windows' way with case
        assertThat(Selection.fromFileName("inicia_7zip_vlc (1).exe")).containsExactly("7zip", "vlc");
        assertThat(Selection.fromFileName("Inicia_7zip_VLC(2).EXE")).containsExactly("7zip", "vlc");
        assertThat(Selection.fromFileName("inicia_7zip_7zip.exe")).containsExactly("7zip");
    }

    @Test
    void plainNamesSelectNothing() {
        assertThat(Selection.fromFileName("inicia.exe")).isEmpty();
        assertThat(Selection.fromFileName("setup.exe")).isEmpty();
        assertThat(Selection.fromFileName("inicia_.exe")).isEmpty();
        assertThat(Selection.fromFileName("inicia")).isEmpty();
    }

    @Test
    void listFileWithComments(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("apps.txt");
        Files.writeString(file, """
                # my usual setup
                7zip   Notepad-Plus-Plus
                vlc    # media

                """);
        assertThat(Selection.fromListFile(file)).containsExactly("7zip", "notepad-plus-plus", "vlc");
    }
}
