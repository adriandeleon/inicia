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
        this(userAgent, Duration.ofSeconds(20));
    }

    /**
     * The proxy, if any, is the default ProxySelector's (set up by the command line), and its
     * password the default Authenticator's.
     */
    public Downloader(String userAgent, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(connectTimeout);
        java.net.Authenticator auth = java.net.Authenticator.getDefault();
        if (auth != null) {
            builder.authenticator(auth);
        }
        this.client = builder.build();
        this.userAgent = userAgent;
    }

    /**
     * A small file, read whole, tried once: for the catalog, which mustn't keep a window waiting.
     *
     * @param limit the most bytes it may have
     */
    public byte[] read(URI url, Duration timeout, int limit) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new Refused("refusing to download over " + url.getScheme() + ": " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("User-Agent", userAgent)
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 407) {
                throw new Refused(PROXY_LOGIN);
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + response.uri().getHost());
            }
            if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                throw new Refused("redirected off https, to " + response.uri());
            }
            byte[] bytes = body.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException(url + " is larger than " + limit + " bytes");
            }
            return bytes;
        }
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

    static final String PROXY_LOGIN = "the proxy server wants a user name and password: "
            + "give them with --proxy http://user:password@host:port, or in INICIA_PROXY";

    /**
     * What went wrong, in words: some network errors (a refused connection, say) come with no
     * message of their own, so their kind and cause say it instead.
     */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 300; t = t.getCause()) {
            String what = t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage()
                    : t.getClass().getSimpleName().replaceAll("(?<=[a-z])(?=[A-Z])", " ").toLowerCase(java.util.Locale.ROOT);
            if (sb.indexOf(what) < 0) {
                sb.append(sb.isEmpty() ? "" : ": ").append(what);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.toString();
    }

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
                    throw new IOException(describe(e) + " (tried " + ATTEMPTS + " times)", e);
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
            if (status == 407) {
                throw new Refused(PROXY_LOGIN);
            }
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
