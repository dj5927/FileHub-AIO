package com.nasmount.mobile;

import com.hierynomus.sshj.key.KeyAlgorithm;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.common.Factory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    static DefaultConfig androidCompatible() {
        DefaultConfig config = new DefaultConfig();
        List<Factory.Named<KeyAlgorithm>> keys = new ArrayList<>(
                config.getKeyAlgorithms());
        keys.sort(Comparator.comparingInt(
                factory -> preference(factory.getName())));
        config.setKeyAlgorithms(keys);
        return config;
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
