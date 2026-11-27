package dev.inicia.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** How an installer is run silently. Catalog entries add their own arguments after these. */
public enum InstallerType {
    /** Windows Installer package, run through msiexec. */
    MSI(List.of("/qn", "/norestart")),
    /** Inno Setup. */
    INNO(List.of("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-")),
    /** Nullsoft (NSIS), and installers that take NSIS's /S, such as 7-Zip's. */
    NSIS(List.of("/S")),
    /** Anything else: the catalog entry gives every argument. */
    EXE(List.of());

    private final List<String> silentArgs;

    InstallerType(List<String> silentArgs) {
        this.silentArgs = silentArgs;
    }

    public List<String> silentArgs() {
        return silentArgs;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<InstallerType> parse(String s) {
        for (InstallerType t : values()) {
            if (t.id().equals(s.toLowerCase(Locale.ROOT))) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
