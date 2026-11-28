package dev.inicia.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxiesTest {

    @Test
    void proxyAddressesInTheUsualForms() {
        assertThat(Proxies.parse("proxy.corp:3128")).isEqualTo(new Proxies.Server("proxy.corp", 3128, null, null));
        assertThat(Proxies.parse("http://10.0.0.5:8080/")).isEqualTo(new Proxies.Server("10.0.0.5", 8080, null, null));
        assertThat(Proxies.parse("http://proxy")).isEqualTo(new Proxies.Server("proxy", 8080, null, null));
        assertThat(Proxies.parse("http://ada:p%40ss@proxy:3128"))
                .isEqualTo(new Proxies.Server("proxy", 3128, "ada", "p@ss"));
        assertThatThrownBy(() -> Proxies.parse("socks5://proxy:1080")).hasMessageContaining("isn't host:port");
        assertThatThrownBy(() -> Proxies.parse("::")).hasMessageContaining("isn't host:port");
    }

    @Test
    void noProxyBecomesJavasNonProxyHosts() {
        assertThat(Proxies.nonProxyHosts("localhost, .corp.example,10.1.2.3"))
                .isEqualTo("localhost|*.corp.example|10.1.2.3");
    }
}
