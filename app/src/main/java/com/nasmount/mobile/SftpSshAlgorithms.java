package com.nasmount.mobile;

import com.hierynomus.sshj.key.KeyAlgorithm;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.DefaultSecurityProviderConfig;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.kex.KeyExchange;
import net.schmizz.sshj.transport.cipher.Cipher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.security.GeneralSecurityException;

/**
 * Android SSH host-key preference for recent SteamOS/OpenSSH servers.
 *
 * SSHJ v0.41.x advertises ssh-ed25519 before ECDSA, but some Android
 * Conscrypt builds represent Ed25519 public keys as OpenSslEdDsaPublicKey;
 * SSHJ's host-key decoder/verifier can throw a TransportException before
 * the application's host-key callback ever runs. A modern, secure ECDSA
 * host key avoids that provider-specific path on dual-key NAS servers.
 *
 * Ed25519 remains offered for Ed25519-only hosts; no insecure ssh-rsa/DSA
 * algorithm is enabled or promoted. Fingerprint pinning remains mandatory.
 */
final class SftpSshAlgorithms {
    private SftpSshAlgorithms() {}

    static synchronized DefaultConfig androidCompatible() {
        // Android 9+ removes SHA-256 and other JCA primitives from the
        // system BC provider. SSHJ's DefaultConfig may bind *all* crypto to
        // BC, producing NoSuchAlgorithmException during the handshake.
        // DefaultSecurityProviderConfig is the upstream SSHJ-supported way
        // to use Android's real Conscrypt/platform JCA implementations.
        SecurityUtils.setSecurityProvider(null);
        SecurityUtils.setRegisterBouncyCastle(false);
        DefaultConfig config = new DefaultSecurityProviderConfig();

        // Avoid requesting Curve25519/X25519 when Android's configured JCA
        // provider does not expose it. The Steam Deck OpenSSH server offers
        // ECDH NIST P-256, and Android supports EC/ECDH in its platform JCA.
        List<Factory.Named<KeyExchange>> kex = new ArrayList<>(
                config.getKeyExchangeFactories());
        kex.sort(Comparator.comparingInt(f -> kexPreference(f.getName())));
        config.setKeyExchangeFactories(kex);

        // Prefer Android Conscrypt AES/CTR rather than a provider-dependent
        // ChaCha implementation. Both algorithms are secure and supported
        // by modern OpenSSH/SteamOS. Do not enable legacy weak ciphers.
        List<Factory.Named<Cipher>> ciphers = new ArrayList<>(
                config.getCipherFactories());
        ciphers.sort(Comparator.comparingInt(f -> cipherPreference(f.getName())));
        config.setCipherFactories(ciphers);

        List<Factory.Named<KeyAlgorithm>> keys = new ArrayList<>(
                config.getKeyAlgorithms());
        keys.sort(Comparator.comparingInt(
                factory -> preference(factory.getName())));
        config.setKeyAlgorithms(keys);
        return config;
    }

    static void verifyRequiredCrypto() throws GeneralSecurityException {
        // Exercised on the actual Android device before we start SSH.
        // This also catches stale explicit BC provider registration.
        SecurityUtils.getMessageDigest("SHA-256");
        SecurityUtils.getKeyPairGenerator("EC");
        SecurityUtils.getKeyAgreement("ECDH");
        SecurityUtils.getKeyFactory("EC");
        SecurityUtils.getSignature("SHA256withECDSA");
        SecurityUtils.getCipher("AES/CTR/NoPadding");
        SecurityUtils.getMAC("HmacSHA256");
    }

    static int kexPreference(String name) {
        if ("ecdh-sha2-nistp256".equals(name)) return 0;
        if ("ecdh-sha2-nistp384".equals(name)) return 1;
        if ("ecdh-sha2-nistp521".equals(name)) return 2;
        if ("diffie-hellman-group14-sha256".equals(name)) return 3;
        if ("curve25519-sha256".equals(name)) return 10;
        if ("curve25519-sha256@libssh.org".equals(name)) return 11;
        return 100;
    }

    static int cipherPreference(String name) {
        if ("aes128-ctr".equals(name)) return 0;
        if ("aes256-ctr".equals(name)) return 1;
        if ("aes192-ctr".equals(name)) return 2;
        if ("aes128-gcm@openssh.com".equals(name)) return 3;
        if ("aes256-gcm@openssh.com".equals(name)) return 4;
        if ("chacha20-poly1305@openssh.com".equals(name)) return 10;
        return 100;
    }

    static int preference(String name) {
        if ("ecdsa-sha2-nistp256".equals(name)) return 0;
        if ("ecdsa-sha2-nistp384".equals(name)) return 1;
        if ("ecdsa-sha2-nistp521".equals(name)) return 2;
        if ("rsa-sha2-512".equals(name)) return 3;
        if ("rsa-sha2-256".equals(name)) return 4;
        if ("ssh-ed25519".equals(name)) return 5;
        // Keep the rest of SSHJ's order, but never prioritize legacy
        // ssh-rsa or ssh-dss over modern algorithms.
        return 100;
    }
}
