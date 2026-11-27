package dev.inicia.windows;

import java.io.IOException;
import java.util.Map;

/** Plain Windows message boxes, for when there's no window or console to say something in. */
public final class Dialogs {

    private Dialogs() {
    }

    public static void message(String title, String text) throws IOException, InterruptedException {
        PowerShell.run("""
                Add-Type -AssemblyName System.Windows.Forms
                [void][System.Windows.Forms.MessageBox]::Show($env:INICIA_TEXT, $env:INICIA_TITLE, 'OK', 'Information')
                """, Map.of("INICIA_TITLE", title, "INICIA_TEXT", text));
    }
}
