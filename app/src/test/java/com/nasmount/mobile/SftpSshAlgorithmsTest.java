package com.nasmount.mobile;

import com.hierynomus.sshj.key.KeyAlgorithm;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.kex.KeyExchange;
import net.schmizz.sshj.transport.cipher.Cipher;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SftpSshAlgorithmsTest {
    @Test public void modernEcdsaHostKeyPrecedesAndroidConscryptEd25519() {
        DefaultConfig config = SftpSshAlgorithms.androidCompatible();
        List<Factory.Named<KeyAlgorithm>> keys = config.getKeyAlgorithms();
        assertFalse(keys.isEmpty());
        assertEquals("ecdsa-sha2-nistp256", keys.get(0).getName());
        int ecdsaIndex = -1;
        int ed25519Index = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).getName().equals("ecdsa-sha2-nistp256"))
                ecdsaIndex = i;
            if (keys.get(i).getName().equals("ssh-ed25519"))
                ed25519Index = i;
        }
        assertTrue(ecdsaIndex >= 0 && ed25519Index > ecdsaIndex);
        // Keeps Ed25519-only hosts accessible; no security checks disabled.
        assertTrue(ed25519Index >= 0);
    }

    @Test public void insecureHostKeyAlgorithmsAreNotPromoted() {
        assertTrue(SftpSshAlgorithms.preference("ssh-rsa") >
                SftpSshAlgorithms.preference("rsa-sha2-256"));
        assertTrue(SftpSshAlgorithms.preference("ssh-dss") >
                SftpSshAlgorithms.preference("ecdsa-sha2-nistp256"));
        assertTrue(SftpSshAlgorithms.preference("ssh-ed25519") >
                SftpSshAlgorithms.preference("ecdsa-sha2-nistp256"));
    }

    @Test public void brokenExplicitBcProviderIsResetBeforeSshHandshake()
            throws Exception {
        // Simulates an SSHJ provider pinned to Android's missing-crypto BC.
        SecurityUtils.setSecurityProvider("NON_EXISTENT_ANDROID_BC_STUB");
        DefaultConfig config = SftpSshAlgorithms.androidCompatible();
        assertNull("Do not pin Android's deprecated BC provider",
                SecurityUtils.getSecurityProvider());
        SftpSshAlgorithms.verifyRequiredCrypto();
        assertNotNull(SecurityUtils.getMessageDigest("SHA-256"));
        assertFalse(config.getKeyExchangeFactories().isEmpty());
    }

    @Test public void androidEcdhAndPlatformAesPrecedeProviderDependentKex()
            throws Exception {
        DefaultConfig config = SftpSshAlgorithms.androidCompatible();
        List<Factory.Named<KeyExchange>> exchanges =
                config.getKeyExchangeFactories();
        assertEquals("ecdh-sha2-nistp256", exchanges.get(0).getName());
        assertTrue(exchanges.stream()
                .anyMatch(k -> k.getName().equals("curve25519-sha256")));
        List<Factory.Named<Cipher>> ciphers = config.getCipherFactories();
        assertEquals("aes128-ctr", ciphers.get(0).getName());
        // SSHJ can omit ChaCha entirely when BC is disabled. In either
        // case, Android-supported AES must remain the first offered cipher.
        assertTrue(ciphers.stream()
                .anyMatch(k -> k.getName().equals("aes256-ctr")));
        SftpSshAlgorithms.verifyRequiredCrypto();
    }
}
