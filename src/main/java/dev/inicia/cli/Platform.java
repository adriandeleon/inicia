package dev.inicia.cli;

import dev.inicia.catalog.Arch;
import dev.inicia.engine.InstalledApp;
import dev.inicia.engine.InstalledApps;
import dev.inicia.engine.SignatureCheck;
import dev.inicia.windows.Authenticode;
import dev.inicia.windows.WindowsInstalledApps;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** The pieces that differ between Windows and the machines inicia is developed on. */
final class Platform {

    private Platform() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static Arch arch() {
        return Arch.ofMachine(System.getenv());
    }

    static InstalledApps installedApps() {
        return isWindows() ? new WindowsInstalledApps() : List::<InstalledApp>of;
    }

    static List<InstalledApp> installed() throws IOException, InterruptedException {
        return installedApps().list();
    }

    static SignatureCheck signatures() {
        return isWindows() ? new Authenticode() : Authenticode.unavailable();
    }

    /** Whether this is the native exe (not java -jar), which can start itself again elevated. */
    static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }
}
