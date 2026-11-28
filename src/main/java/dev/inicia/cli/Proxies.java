package dev.inicia.cli;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The proxy server downloads go through. In order: --proxy; else the INICIA_PROXY, HTTPS_PROXY
 * or ALL_PROXY environment variable (with NO_PROXY); else Windows' own proxy settings, which Java
 * reads through WinHTTP, a proxy auto-config (PAC) script included. A proxy may carry a user name
 * and password (http://user:password@proxy:8080), for proxies that ask for Basic authentication.
 */
final class Proxies {

    private Proxies() {
    }

    /** Sets the proxy for everything inicia downloads from here on. */
    static void apply(String explicit) {
        apply(explicit, System.getenv());
    }

    static void apply(String explicit, Map<String, String> env) {
        // Windows' settings, when nothing more specific is given; read on first use
        System.setProperty("java.net.useSystemProxies", "true");
        String spec = explicit != null && !explicit.isBlank() ? explicit
                : first(env, "INICIA_PROXY", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy");
        if (spec == null) {
            return;
        }
        Server server = parse(spec);
        for (String scheme : List.of("https", "http")) {
            System.setProperty(scheme + ".proxyHost", server.host());
            System.setProperty(scheme + ".proxyPort", Integer.toString(server.port()));
        }
        String noProxy = first(env, "NO_PROXY", "no_proxy");
        if (noProxy != null) {
            System.setProperty("http.nonProxyHosts", nonProxyHosts(noProxy));
        }
        if (server.user() != null) {
            // Java turns Basic off for proxies by default (it sends the password in the clear to the
            // proxy); someone who gave a proxy password wants it used
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            PasswordAuthentication login = new PasswordAuthentication(server.user(), server.password().toCharArray());
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY ? login : null;
                }
            });
        }
    }

    record Server(String host, int port, String user, String password) {
    }

    /** host:port, or a URL: http://host:port, http://user:password@host:port. */
    static Server parse(String spec) {
        String s = spec.strip();
        URI uri;
        try {
            uri = URI.create(s.contains("://") ? s : "http://" + s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the proxy \"" + spec + "\" isn't host:port or http://host:port");
        }
        if (uri.getHost() == null || !List.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("the proxy \"" + spec + "\" isn't host:port or http://host:port");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 8080;
        String user = null;
        String password = null;
        if (uri.getRawUserInfo() != null) {
            String info = uri.getRawUserInfo();
            int colon = info.indexOf(':');
            user = URLDecoder.decode(colon < 0 ? info : info.substring(0, colon), StandardCharsets.UTF_8);
            password = colon < 0 ? "" : URLDecoder.decode(info.substring(colon + 1), StandardCharsets.UTF_8);
        }
        return new Server(uri.getHost(), port, user, password);
    }

    /** NO_PROXY's "localhost,.corp.example,10.1.2.3" as Java's "localhost|*.corp.example|10.1.2.3". */
    static String nonProxyHosts(String noProxy) {
        List<String> hosts = new ArrayList<>();
        for (String h : noProxy.split("[,\\s]+")) {
            if (h.isBlank()) {
                continue;
            }
            hosts.add(h.startsWith(".") ? "*" + h : h);
        }
        return String.join("|", hosts);
    }

    /** The proxy a download from this address goes through, as host:port; empty when it goes direct. */
    static Optional<String> describe(URI url) {
        try {
            for (Proxy p : ProxySelector.getDefault().select(url)) {
                if (p.type() != Proxy.Type.DIRECT && p.address() instanceof InetSocketAddress a) {
                    return Optional.of(a.getHostString() + ":" + a.getPort());
                }
            }
        } catch (RuntimeException e) {
            // no answer from Windows' settings: downloads go direct
        }
        return Optional.empty();
    }

    private static String first(Map<String, String> env, String... names) {
        for (String n : names) {
            String v = env.get(n);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }
}
