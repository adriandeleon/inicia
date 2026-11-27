package dev.inicia.catalog;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * One downloadable installer of an app.
 *
 * @param arch   the architecture it installs
 * @param type   how it's run silently
 * @param url    https download address; redirects are followed, but never to plain http
 * @param sha256 the file's SHA-256, lowercase hex; empty for a "latest" URL whose file changes
 * @param signer the Authenticode signer's name (the certificate's CN or O), checked on Windows; at
 *               least one of sha256 and signer is always present
 * @param args   arguments after the type's own silent ones
 */
public record Installer(Arch arch, InstallerType type, URI url, Optional<String> sha256,
                        Optional<String> signer, List<String> args) {

    public Installer {
        args = List.copyOf(args);
    }

    /** The file name to save the download as: the URL's last path segment, or a safe fallback. */
    public String fileName(String appId) {
        String path = url.getPath();
        String last = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (last.matches("[A-Za-z0-9._ -]+\\.(exe|msi)")) {
            return last;
        }
        return appId + "-setup." + (type == InstallerType.MSI ? "msi" : "exe");
    }
}
