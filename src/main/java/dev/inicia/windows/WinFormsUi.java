package dev.inicia.windows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * inicia's window: a Windows Forms script (ui.ps1) run by Windows PowerShell, which every
 * Windows 10 and 11 PC has, so inicia stays a single exe with no UI libraries beside it. inicia
 * writes events to the script's stdin as JSON lines and reads its answers from its stdout.
 */
public final class WinFormsUi implements AutoCloseable {

    /** What {@link #next} returns once the window has closed. */
    public static final String CLOSED = "closed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Process process;
    private final Writer toWindow;
    private final BlockingQueue<String> fromWindow = new LinkedBlockingQueue<>();

    private WinFormsUi(Process process) {
        this.process = process;
        this.toWindow = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    fromWindow.add(line.strip());
                }
            } catch (IOException e) {
                // the window went away; CLOSED below says so
            }
            fromWindow.add(CLOSED);
        }, "inicia-window");
        reader.setDaemon(true);
        reader.start();
    }

    /** Opens the window and waits until it can take events. */
    public static WinFormsUi open() throws IOException, InterruptedException {
        String script;
        try (InputStream in = WinFormsUi.class.getResourceAsStream("/dev/inicia/windows/ui.ps1")) {
            if (in == null) {
                throw new IOException("the window's script is missing");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // the script itself comes in on stdin, so it never touches the disk (where another
        // program could change it before an elevated inicia runs it) and has no length limit
        String bootstrap = "$s = [Console]::In.ReadLine(); "
                + "& ([scriptblock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($s))))";
        String encoded = Base64.getEncoder().encodeToString(bootstrap.getBytes(StandardCharsets.UTF_16LE));
        Process p = new ProcessBuilder(PowerShell.exe().toString(), "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        WinFormsUi ui = new WinFormsUi(p);
        ui.toWindow.write(Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8)) + "\n");
        ui.toWindow.flush();
        String first = ui.fromWindow.poll(60, TimeUnit.SECONDS);
        if (!"ready".equals(first)) {
            p.destroyForcibly();
            throw new IOException("the window didn't open" + (first == null ? "" : " (" + first + ")"));
        }
        return ui;
    }

    public static ObjectNode event(String type) {
        return JSON.createObjectNode().put("t", type);
    }

    /** Sends an event; a window that has closed meanwhile is ignored. */
    public synchronized void send(ObjectNode event) {
        try {
            toWindow.write(JSON.writeValueAsString(event) + "\n");
            toWindow.flush();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            // closed
        }
    }

    /** The window's next answer, waiting for it: "install …", "cancel", "quit", or {@link #CLOSED}. */
    public String next() throws InterruptedException {
        return fromWindow.take();
    }

    /** Puts an answer back at the front, for the next {@link #next} to return. */
    public void putBack(String answer) {
        java.util.List<String> rest = new java.util.ArrayList<>();
        fromWindow.drainTo(rest);
        fromWindow.add(answer);
        fromWindow.addAll(rest);
    }

    /** The window's next answer if there is one now, else null. */
    public String poll() {
        return fromWindow.poll();
    }

    /** Waits for the person to close the window. */
    public void waitClosed() throws InterruptedException {
        process.waitFor();
    }

    @Override
    public void close() {
        try {
            toWindow.close();
        } catch (IOException e) {
            // already closed
        }
    }
}
