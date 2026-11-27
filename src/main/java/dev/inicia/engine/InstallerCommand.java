package dev.inicia.engine;

import dev.inicia.catalog.Installer;
import dev.inicia.catalog.InstallerType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The command line that runs an installer silently, and what its exit code means. */
public final class InstallerCommand {

    public enum Outcome { OK, OK_REBOOT, FAILED }

    private InstallerCommand() {
    }

    /**
     * @param msiLog where msiexec writes its verbose log (kept for a failed install), or null
     */
    public static List<String> build(Installer installer, Path file, Path msiLog) {
        List<String> cmd = new ArrayList<>();
        if (installer.type() == InstallerType.MSI) {
            cmd.add("msiexec.exe");
            cmd.add("/i");
            cmd.add(file.toString());
            cmd.addAll(installer.type().silentArgs());
            if (msiLog != null) {
                cmd.add("/l*v");
                cmd.add(msiLog.toString());
            }
        } else {
            cmd.add(file.toString());
            cmd.addAll(installer.type().silentArgs());
        }
        cmd.addAll(installer.args());
        return cmd;
    }

    public static Outcome outcome(int exitCode) {
        return switch (exitCode) {
            case 0 -> Outcome.OK;
            // ERROR_SUCCESS_REBOOT_REQUIRED, ERROR_SUCCESS_REBOOT_INITIATED
            case 3010, 1641 -> Outcome.OK_REBOOT;
            default -> Outcome.FAILED;
        };
    }

    /** A reason for the common Windows Installer failures, when there is one to give. */
    public static Optional<String> explain(int exitCode) {
        return Optional.ofNullable(switch (exitCode) {
            case 1602 -> "the install was cancelled";
            case 1603 -> "the installer hit a fatal error";
            case 1618 -> "another installation is already running; try again when it's done";
            case 1619 -> "the package couldn't be opened";
            case 1625 -> "a system policy forbids this install";
            case 1638 -> "another version is already installed; uninstall it first";
            case 5 -> "access denied; run inicia as administrator";
            default -> null;
        });
    }
}
