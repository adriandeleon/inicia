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
        } else if (installer.type() == InstallerType.MSIX) {
            // Add-AppxPackage, for the account this runs as; the script goes base64-encoded, so no
            // quoting survives the command line. The exit code is the deployment's HRESULT, which
            // Windows PowerShell only puts in the message ("Deployment failed with HRESULT: 0x80073D02")
            String path = file.toString().replace("'", "''");
            String script = "$ProgressPreference = 'SilentlyContinue'; $ErrorActionPreference = 'Stop'; "
                    + "try { Add-AppxPackage -Path '" + path + "'; exit 0 } "
                    + "catch { $h = $_.Exception.HResult; "
                    + "if ($_.Exception.Message -match 'HRESULT: 0x([0-9A-Fa-f]{8})') { $h = [Convert]::ToInt32($Matches[1], 16) }; "
                    + "if ($h -eq 0) { $h = 1 }; exit $h }";
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            cmd.add(Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString());
            cmd.addAll(List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
                    java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE))));
            return cmd;
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
            // Add-AppxPackage's HRESULTs, for an msix package
            case 0x80073D02 -> "the app is open; close it and try again";
            case 0x80073D06 -> "a newer version is already installed";
            case 0x80073CFF -> "a system policy blocks apps from outside the Microsoft Store";
            case 0x80073CF3 -> "the package conflicts with what's installed, or needs a component that isn't";
            case 0x80073CF0 -> "the package couldn't be opened";
            default -> null;
        });
    }
}
