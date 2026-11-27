package dev.inicia.cli;

import dev.inicia.windows.Dialogs;

/** A message for someone who has no console to read: a dialog on Windows, stderr elsewhere. */
final class Messages {

    private Messages() {
    }

    static void show(String text) {
        if (Platform.isWindows()) {
            try {
                Dialogs.message("inicia", text);
                return;
            } catch (Exception e) {
                // fall through to stderr
            }
        }
        System.err.println("inicia: " + text);
    }
}
