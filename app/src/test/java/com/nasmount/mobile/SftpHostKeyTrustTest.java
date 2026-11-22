package com.nasmount.mobile;

import org.junit.Test;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SftpHostKeyTrustTest {
    /**
     * Emulates Android Conscrypt's EdDSA key wrapper: valid X.509 Ed25519
     * bytes, but a PublicKey class unknown to SSHJ's concrete-key encoder.
     */
    private static PublicKey androidEdDsaLike(PublicKey original) {
        return new PublicKey() {
            @Override public String getAlgorithm() { return "EdDSA"; }
            @Override public String getFormat() { return "X.509"; }
            @Override public byte[] getEncoded() {
                return original.getEncoded().clone();
            }
        };
    }

    @Test public void androidEd25519OpenSshFingerprintIsStable() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String nativeFp = SftpHostKeyTrust.fingerprint(pair.getPublic());
        String androidFp = SftpHostKeyTrust.fingerprint(
                androidEdDsaLike(pair.getPublic()));
        assertTrue(androidFp.startsWith("SHA256:"));
        assertEquals(nativeFp, androidFp);
        assertFalse(androidFp.contains("="));
    }

    @Test public void firstConnectionReturnsConfirmationInsteadOfTransportError()
            throws Exception {
        PublicKey key = androidEdDsaLike(
                KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair().getPublic());
        SftpHostKeyTrust trust = new SftpHostKeyTrust("");
        assertFalse(trust.verify(key));
        try {
            trust.rethrowIfRejected();
            fail("Must ask the user to confirm the first server host key");
        } catch (RemoteProvider.UntrustedHostKey expected) {
            assertEquals(SftpHostKeyTrust.fingerprint(key),
                    expected.fingerprint);
        }
    }

    @Test public void recognizedHostKeyConnectsButChangedKeyFailsClosed()
            throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String expected = SftpHostKeyTrust.fingerprint(pair.getPublic());
        SftpHostKeyTrust trusted = new SftpHostKeyTrust(expected);
        assertTrue(trusted.verify(androidEdDsaLike(pair.getPublic())));
        trusted.rethrowIfRejected();
        PublicKey changed = KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair().getPublic();
        SftpHostKeyTrust mismatch = new SftpHostKeyTrust(expected);
        assertFalse(mismatch.verify(changed));
        assertThrows(SecurityException.class, mismatch::rethrowIfRejected);
    }

    @Test public void malformedEd25519KeyIsNeverAccepted() throws Exception {
        PublicKey invalid = new PublicKey() {
            @Override public String getAlgorithm() { return "Ed25519"; }
            @Override public String getFormat() { return "X.509"; }
            @Override public byte[] getEncoded() { return new byte[44]; }
        };
        SftpHostKeyTrust trust = new SftpHostKeyTrust("");
        assertFalse(trust.verify(invalid));
        assertThrows(IOException.class, trust::rethrowIfRejected);
    }

    @Test public void rsaFingerprintRemainsSupported() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String digest = SftpHostKeyTrust.fingerprint(gen.generateKeyPair().getPublic());
        assertTrue(digest.startsWith("SHA256:"));
    }
}
