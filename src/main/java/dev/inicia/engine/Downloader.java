package dev.inicia.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads over https only. Redirects are followed (mirrors, GitHub's release storage), but
 * HttpClient's NORMAL policy never follows one from https to http, and the final address is
 * checked again. The SHA-256 is computed while the file streams to disk.
 */
public class Downloader {

    public record Download(Path file, String sha256, long size, URI from) {
    }

    /** Bytes so far, and the total when the server said (else -1). */
    public interface Listener {
        void progress(long bytes, long total);
    }

    private final HttpClient client;
    private final String userAgent;

    public Downloader(String userAgent) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.userAgent = userAgent;
    }

    public Download fetch(URI url, Path target) throws IOException, InterruptedException {
        return fetch(url, target, (bytes, total) -> { });
    }

    /** A download that trying again won't fix: plain http, a redirect off https, a 4xx answer. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    interface Attempt<T> {
        T run() throws IOException, InterruptedException;
    }

    static final int ATTEMPTS = 3;

    /**
     * Runs {@code attempt} until it works, {@link #ATTEMPTS} times at most, waiting a little longer
     * each time. Mirror networks (LibreOffice's, SourceForge's) send each try to a mirror of their
     * choosing, and the odd one is down or has a broken certificate; a {@link Refused} download
     * isn't tried again.
     */
    static <T> T withRetries(Attempt<T> attempt, java.util.function.LongConsumer sleep)
            throws IOException, InterruptedException {
        for (int i = 1; ; i++) {
            try {
                return attempt.run();
            } catch (Refused e) {
                throw e;
            } catch (IOException e) {
                if (i == ATTEMPTS) {
                    throw new IOException(e.getMessage() + " (tried " + ATTEMPTS + " times)", e);
                }
                sleep.accept(2000L * i);
            }
        }
    }

    public Download fetch(URI url, Path target, Listener listener) throws IOException, InterruptedException {
        return withRetries(() -> fetchOnce(url, target, listener), ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private Download fetchOnce(URI url, Path target, Listener listener) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new Refused("refusing to download over " + url.getScheme() + ": " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        URI from = response.uri();
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 400 && status < 500) {
                throw new Refused("HTTP " + status + " from " + from.getHost());
            }
            if (status != 200) {
                throw new IOException("HTTP " + status + " from " + from.getHost());
            }
            if (!"https".equalsIgnoreCase(from.getScheme())) {
                throw new Refused("the download was redirected off https, to " + from);
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            MessageDigest sha = sha256();
            Path part = target.resolveSibling(target.getFileName() + ".part");
            long size = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = body.read(buf)) > 0; ) {
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                    size += n;
                    listener.progress(size, total);
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return new Download(target, HexFormat.of().formatHex(sha.digest()), size, from);
        }
    }

    /** The SHA-256 of a local file, lowercase hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                sha.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
