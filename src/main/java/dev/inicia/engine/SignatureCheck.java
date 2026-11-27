package dev.inicia.engine;

import java.io.IOException;
import java.nio.file.Path;

/** Checks a file's Authenticode signature. */
public interface SignatureCheck {

    /**
     * @param valid   the signature is present, intact, and chains to a trusted root
     * @param subject the signing certificate's subject ({@code CN=Mozilla Corporation, O=...}), or ""
     * @param status  Windows' own word for it ({@code Valid}, {@code NotSigned}, {@code HashMismatch})
     */
    record Result(boolean valid, String subject, String status) {

        /** Whether the subject's CN or O is exactly {@code signer}, ignoring case. */
        public boolean signedBy(String signer) {
            for (String part : subject.split(",(?=\\s*[A-Za-z]+=)")) {
                String p = part.strip();
                int eq = p.indexOf('=');
                if (eq > 0) {
                    String key = p.substring(0, eq).strip();
                    String value = p.substring(eq + 1).strip().replaceAll("^\"|\"$", "");
                    if ((key.equalsIgnoreCase("CN") || key.equalsIgnoreCase("O")) && value.equalsIgnoreCase(signer)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    Result check(Path file) throws IOException, InterruptedException;
}
