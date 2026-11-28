package dev.inicia.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedCatalogTest {

    private static final byte[] CATALOG = """
            { "catalog": 1, "apps": [ { "id": "tool", "name": "Tool", "version": "2.0",
              "installers": [ { "arch": "x64", "type": "msi", "url": "https://example.com/tool.msi", "signer": "Example" } ] } ] }
            """.getBytes(StandardCharsets.UTF_8);

    private final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

    SignedCatalogTest() throws Exception {
    }

    /** What the site's deploy job writes: openssl pkeyutl -sign -rawin over the prefix and the catalog. */
    private static String sign(PrivateKey key, String published, byte[] catalog) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(SignedCatalog.signedPrefix(published));
        s.update(catalog);
        return "inicia-catalog-signature 1\npublished " + published + "\ned25519 "
                + Base64.getEncoder().encodeToString(s.sign()) + "\n";
    }

    @Test
    void aSignedCatalogIsTaken() throws Exception {
        String sig = sign(keys.getPrivate(), "2026-11-28T04:12:09Z", CATALOG);
        SignedCatalog.Verified v = SignedCatalog.verify(CATALOG, sig, keys.getPublic(), Instant.parse("2026-11-27T00:00:00Z"));
        assertThat(v.published()).isEqualTo(Instant.parse("2026-11-28T04:12:09Z"));
        assertThat(v.catalog().app("tool")).isPresent();
    }

    @Test
    void theKeyFileHoldsTheBase64OfTheX509Key() throws Exception {
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertThat(SignedCatalog.publicKey(pub + "\n")).isEqualTo(keys.getPublic());
    }

    @Test
    void aChangedCatalogOrAnotherKeyIsRefused() throws Exception {
        String sig = sign(keys.getPrivate(), "2026-11-28T04:12:09Z", CATALOG);
        byte[] changed = new String(CATALOG, StandardCharsets.UTF_8).replace("2.0", "2.1").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SignedCatalog.verify(changed, sig, keys.getPublic(), Instant.EPOCH))
                .hasMessage("its signature doesn't match");

        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String forged = sign(other.getPrivate(), "2026-11-28T04:12:09Z", CATALOG);
        assertThatThrownBy(() -> SignedCatalog.verify(CATALOG, forged, keys.getPublic(), Instant.EPOCH))
                .hasMessage("its signature doesn't match");

        // the published time is signed too: moving it forward breaks the signature
        String moved = sig.replace("2026-11-28T04:12:09Z", "2027-01-01T00:00:00Z");
        assertThatThrownBy(() -> SignedCatalog.verify(CATALOG, moved, keys.getPublic(), Instant.EPOCH))
                .hasMessage("its signature doesn't match");
    }

    @Test
    void anOlderCatalogThanTheBuiltInOneIsRefused() throws Exception {
        String sig = sign(keys.getPrivate(), "2026-11-01T00:00:00Z", CATALOG);
        assertThatThrownBy(() -> SignedCatalog.verify(CATALOG, sig, keys.getPublic(), Instant.parse("2026-11-20T00:00:00Z")))
                .hasMessageStartingWith("it's older than the catalog built into this inicia");
    }

    @Test
    void aDamagedOrForeignSignatureFileIsRefused() {
        assertThatThrownBy(() -> SignedCatalog.verify(CATALOG, "<html>Not found</html>", keys.getPublic(), Instant.EPOCH))
                .hasMessage("the signature file isn't one inicia reads");
        assertThatThrownBy(() -> SignedCatalog.verify(CATALOG, "inicia-catalog-signature 1\npublished 2026-11-28T04:12:09Z\n",
                keys.getPublic(), Instant.EPOCH)).hasMessage("the signature file is incomplete");
    }

    @Test
    void aSignedCatalogThisVersionCantReadIsRefused() throws Exception {
        byte[] future = "{ \"catalog\": 2, \"apps\": [] }".getBytes(StandardCharsets.UTF_8);
        String sig = sign(keys.getPrivate(), "2026-11-28T04:12:09Z", future);
        assertThatThrownBy(() -> SignedCatalog.verify(future, sig, keys.getPublic(), Instant.EPOCH))
                .hasMessageStartingWith("this inicia can't read it");
    }
}
