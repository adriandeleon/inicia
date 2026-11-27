package dev.inicia.engine;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloaderTest {

    private final List<Long> slept = new ArrayList<>();

    @Test
    void aBrokenMirrorIsTriedAgain() throws Exception {
        AtomicInteger tries = new AtomicInteger();
        String result = Downloader.withRetries(() -> {
            if (tries.incrementAndGet() < 3) {
                throw new SSLHandshakeException("PKIX path building failed");
            }
            return "downloaded";
        }, slept::add);
        assertThat(result).isEqualTo("downloaded");
        assertThat(tries).hasValue(3);
        assertThat(slept).containsExactly(2000L, 4000L);
    }

    @Test
    void givesUpAfterThreeTriesWithTheLastReason() {
        AtomicInteger tries = new AtomicInteger();
        assertThatThrownBy(() -> Downloader.withRetries(() -> {
            tries.incrementAndGet();
            throw new IOException("HTTP 503 from mirror.example");
        }, slept::add))
                .isInstanceOf(IOException.class)
                .hasMessage("HTTP 503 from mirror.example (tried 3 times)");
        assertThat(tries).hasValue(3);
    }

    @Test
    void aRefusalIsNeverTriedAgain() {
        AtomicInteger tries = new AtomicInteger();
        assertThatThrownBy(() -> Downloader.withRetries(() -> {
            tries.incrementAndGet();
            throw new Downloader.Refused("the download was redirected off https, to http://mirror.example/x.exe");
        }, slept::add))
                .hasMessage("the download was redirected off https, to http://mirror.example/x.exe");
        assertThat(tries).hasValue(1);
        assertThat(slept).isEmpty();
    }
}
