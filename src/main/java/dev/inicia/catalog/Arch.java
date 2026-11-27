package dev.inicia.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** A Windows CPU architecture. */
public enum Arch {
    X64, X86, ARM64;

    /** The architectures whose installers run here, best first. ARM64 Windows emulates x64 and x86. */
    public List<Arch> runs() {
        return switch (this) {
            case X64 -> List.of(X64, X86);
            case X86 -> List.of(X86);
            case ARM64 -> List.of(ARM64, X64, X86);
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Arch> parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "x64", "amd64", "x86_64" -> Optional.of(X64);
            case "x86", "i386", "i686" -> Optional.of(X86);
            case "arm64", "aarch64" -> Optional.of(ARM64);
            default -> Optional.empty();
        };
    }

    /**
     * The machine's architecture. A process under emulation sees its own architecture in
     * PROCESSOR_ARCHITECTURE, so PROCESSOR_ARCHITEW6432 (set only then) comes first.
     */
    public static Arch ofMachine(Map<String, String> env) {
        String wow = env.get("PROCESSOR_ARCHITEW6432");
        String arch = wow != null ? wow : env.getOrDefault("PROCESSOR_ARCHITECTURE", System.getProperty("os.arch"));
        return parse(arch).orElse(X64);
    }
}
