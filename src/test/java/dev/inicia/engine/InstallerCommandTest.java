package dev.inicia.engine;

import dev.inicia.catalog.Arch;
import dev.inicia.catalog.Installer;
import dev.inicia.catalog.InstallerType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InstallerCommandTest {

    private static Installer installer(InstallerType type, String... args) {
        return new Installer(Arch.X64, type, URI.create("https://e.com/x"), Optional.of("0".repeat(64)), Optional.empty(), List.of(args), java.util.OptionalLong.empty(), false);
    }

    @Test
    void commandPerType() {
        Path file = Path.of("C:/t/setup.exe");
        assertThat(InstallerCommand.build(installer(InstallerType.NSIS), file, null))
                .containsExactly(file.toString(), "/S");
        assertThat(InstallerCommand.build(installer(InstallerType.INNO, "/MERGETASKS=!desktopicon"), file, null))
                .containsExactly(file.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-", "/MERGETASKS=!desktopicon");
        assertThat(InstallerCommand.build(installer(InstallerType.EXE, "--silent", "--no-launch"), file, null))
                .containsExactly(file.toString(), "--silent", "--no-launch");
        Path msi = Path.of("C:/t/x.msi");
        Path log = Path.of("C:/t/msiexec.log");
        assertThat(InstallerCommand.build(installer(InstallerType.MSI, "ADDDESKTOPICON=0"), msi, log))
                .containsExactly("msiexec.exe", "/i", msi.toString(), "/qn", "/norestart", "/l*v", log.toString(), "ADDDESKTOPICON=0");
    }

    @Test
    void exitCodes() {
        assertThat(InstallerCommand.outcome(0)).isEqualTo(InstallerCommand.Outcome.OK);
        assertThat(InstallerCommand.outcome(3010)).isEqualTo(InstallerCommand.Outcome.OK_REBOOT);
        assertThat(InstallerCommand.outcome(1641)).isEqualTo(InstallerCommand.Outcome.OK_REBOOT);
        assertThat(InstallerCommand.outcome(1603)).isEqualTo(InstallerCommand.Outcome.FAILED);
        assertThat(InstallerCommand.explain(42)).isEmpty();
    }

    @Test
    void signerMatchesCnOrOExactly() {
        SignatureCheck.Result r = new SignatureCheck.Result(true,
                "CN=Mozilla Corporation, OU=Firefox Engineering Operations, O=Mozilla Corporation, L=San Francisco, S=California, C=US", "Valid");
        assertThat(r.signedBy("Mozilla Corporation")).isTrue();
        assertThat(r.signedBy("mozilla corporation")).isTrue();
        assertThat(r.signedBy("Mozilla")).isFalse();
        assertThat(r.signedBy("Firefox Engineering Operations")).isFalse();
        SignatureCheck.Result quoted = new SignatureCheck.Result(true, "CN=\"Example, Inc.\", O=\"Example, Inc.\", C=US", "Valid");
        assertThat(quoted.signedBy("Example, Inc.")).isTrue();
    }
}
