package dev.inicia.windows;

import dev.inicia.engine.SignatureCheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Authenticode through PowerShell's Get-AuthenticodeSignature, which asks Windows (WinVerifyTrust)
 * whether the signature is intact and chains to a trusted root.
 */
public final class Authenticode implements SignatureCheck {

    private static final String SCRIPT = """
            $s = Get-AuthenticodeSignature -LiteralPath $env:INICIA_FILE
            $s.Status.ToString()
            if ($s.SignerCertificate) { $s.SignerCertificate.Subject } else { '' }
            """;

    @Override
    public Result check(Path file) throws IOException, InterruptedException {
        String[] lines = PowerShell.run(SCRIPT, Map.of("INICIA_FILE", file.toAbsolutePath().toString()))
                .strip().split("\\R", 2);
        String status = lines[0].strip();
        String subject = lines.length > 1 ? lines[1].strip() : "";
        return new Result(status.equals("Valid"), subject, status);
    }

    /** On anything but Windows, a signer can't be checked, so an install that needs one fails. */
    public static SignatureCheck unavailable() {
        return file -> new Result(false, "", "Authenticode can only be checked on Windows");
    }
}
