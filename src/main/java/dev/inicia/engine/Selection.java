package dev.inicia.engine;

import dev.inicia.catalog.CatalogReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which apps to install: from the exe's own file name, or from a list file. */
public final class Selection {

    /**
     * The picker page names each download {@code inicia_<id>_<id>....exe}. A browser that already
     * has the file adds " (1)" or "(1)", and Windows' name may differ in case.
     */
    private static final Pattern FILE_NAME = Pattern.compile(
            "(?i)inicia((?:_[a-z0-9-]+)+)\\s*(?:\\(\\d+\\))?\\.exe");

    private Selection() {
    }

    /** The ids in an exe name like {@code inicia_7zip_vlc.exe}; empty for a plain {@code inicia.exe}. */
    public static List<String> fromFileName(String fileName) {
        Matcher m = FILE_NAME.matcher(fileName.strip());
        if (!m.matches()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : m.group(1).substring(1).toLowerCase(Locale.ROOT).split("_")) {
            if (CatalogReader.ID.matcher(id).matches()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** The exe's selection when it runs as a native image, found from its own path. */
    public static List<String> fromThisExe() {
        Optional<String> command = ProcessHandle.current().info().command();
        return command.map(c -> Path.of(c).getFileName().toString()).map(Selection::fromFileName).orElse(List.of());
    }

    /**
     * Ids from a list file: whitespace- or line-separated, {@code #} starts a comment.
     * <pre>
     * # my usual setup
     * 7zip notepad-plus-plus
     * vlc   # media
     * </pre>
     */
    public static List<String> fromListFile(Path file) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            int hash = line.indexOf('#');
            String content = hash >= 0 ? line.substring(0, hash) : line;
            for (String word : content.strip().split("\\s+")) {
                if (!word.isEmpty()) {
                    ids.add(word.toLowerCase(Locale.ROOT));
                }
            }
        }
        return ids;
    }
}
