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

    public Download fetch(URI url, Path target, Listener listener) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IOException("refusing to download over " + url.getScheme() + ": " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        URI from = response.uri();
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + from.getHost());
            }
            if (!"https".equalsIgnoreCase(from.getScheme())) {
                throw new IOException("the download was redirected off https, to " + from);
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
