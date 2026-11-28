package dev.inicia.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;

/**
 * The catalog the site publishes, signed, so an exe downloaded months ago still installs today's
 * versions. The site serves catalog.json and catalog.json.sig:
 *
 * <pre>
 * inicia-catalog-signature 1
 * published 2026-11-28T04:12:09Z
 * ed25519 &lt;base64 signature&gt;
 * </pre>
 *
 * The Ed25519 signature covers {@code "inicia-catalog 1\n" + published + "\n"} followed by
 * catalog.json's exact bytes, made with a key only the site's deploy job holds; the exe has the
 * public half built in. A catalog is only taken when its signature checks out and it was
 * published no earlier than the catalog built into the exe: an old signed catalog, replayed,
 * can't roll back the versions an exe installs.
 */
public final class SignedCatalog {

    public static final String KEY_RESOURCE = "/dev/inicia/catalog-key.pub";

    private SignedCatalog() {
    }

    /** A catalog whose signature checked out, and when it was published. */
    public record Verified(Catalog catalog, Instant published) {
    }

    /** Why a signed catalog wasn't taken. */
    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /** The public key built into inicia; empty in a build that has none (a fork, say). */
    public static Optional<PublicKey> builtInKey() {
        try (InputStream in = SignedCatalog.class.getResourceAsStream(KEY_RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(publicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII)));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A public key as the .pub file holds it: base64 of its X.509 (SubjectPublicKeyInfo) DER. */
    public static PublicKey publicKey(String base64) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(base64.strip());
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * Checks a downloaded catalog against its signature file.
     *
     * @param notBefore the built-in catalog's publication: an older one is refused
     */
    public static Verified verify(byte[] catalog, String signatureFile, PublicKey key, Instant notBefore)
            throws Rejected {
        Instant published = null;
        String publishedText = null;
        byte[] signature = null;
        String[] lines = signatureFile.strip().split("\\R");
        if (lines.length == 0 || !lines[0].strip().equals("inicia-catalog-signature 1")) {
            throw new Rejected("the signature file isn't one inicia reads");
        }
        for (String line : lines) {
            String l = line.strip();
            try {
                if (l.startsWith("published ")) {
                    publishedText = l.substring("published ".length()).strip();
                    published = Instant.parse(publishedText);
                } else if (l.startsWith("ed25519 ")) {
                    signature = Base64.getDecoder().decode(l.substring("ed25519 ".length()).strip());
                }
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new Rejected("the signature file is damaged");
            }
        }
        if (published == null || signature == null) {
            throw new Rejected("the signature file is incomplete");
        }
        try {
            Signature ed = Signature.getInstance("Ed25519");
            ed.initVerify(key);
            ed.update(signedPrefix(publishedText));
            ed.update(catalog);
            if (!ed.verify(signature)) {
                throw new Rejected("its signature doesn't match");
            }
        } catch (GeneralSecurityException e) {
            throw new Rejected("its signature can't be checked: " + e.getMessage());
        }
        if (published.isBefore(notBefore)) {
            throw new Rejected("it's older than the catalog built into this inicia (published " + published + ")");
        }
        try {
            return new Verified(CatalogReader.read("the latest catalog", new String(catalog, StandardCharsets.UTF_8)), published);
        } catch (CatalogException e) {
            // signed, so it's ours: most likely a newer catalog format this inicia doesn't know
            throw new Rejected("this inicia can't read it (" + e.getMessage().lines().findFirst().orElse("") + ")");
        }
    }

    /** What's signed ahead of the catalog's bytes: the published line's text, exactly as written. */
    public static byte[] signedPrefix(String published) {
        return ("inicia-catalog 1\n" + published + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
