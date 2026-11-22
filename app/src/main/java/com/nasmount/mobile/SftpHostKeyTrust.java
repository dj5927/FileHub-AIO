package com.nasmount.mobile;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * Pin the OpenSSH SHA256 host fingerprint without losing first-connection
 * confirmation when SSHJ rejects the host key on its transport thread.
 *
 * Android Conscrypt returns OpenSslEdDsaPublicKey for modern OpenSSH
 * ssh-ed25519 servers. SSHJ's KeyType public-key encoder may not recognize
 * that implementation, although the key's standard Ed25519 SPKI is valid.
 */
final class SftpHostKeyTrust {
    private static final byte[] ED25519_SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b,
            0x65, 0x70, 0x03, 0x21, 0x00
    };

    private final String expected;
    volatile String offered;
    volatile boolean changed;
    volatile Exception error;

    SftpHostKeyTrust(String expected) {
        this.expected = expected == null ? "" : expected.trim();
    }

    boolean verify(PublicKey key) {
        try {
            String current = fingerprint(key);
            offered = current;
            if (expected.isEmpty()) return false; // Require explicit approval.
            boolean matches = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    current.getBytes(StandardCharsets.US_ASCII));
            changed = !matches;
            return matches;
        } catch (Exception failure) {
            error = failure;
            return false; // No unchecked exception on the SSHJ reader thread.
        }
    }

    void rethrowIfRejected() throws IOException {
        if (changed)
            throw new SecurityException(
                    "SFTP 서버 호스트 키가 등록된 지문과 다릅니다. 연결을 차단했습니다.");
        if (error != null)
            throw new IOException("SFTP 서버 키 형식을 확인할 수 없습니다.", error);
        if (expected.isEmpty() && offered != null)
            throw new RemoteProvider.UntrustedHostKey(offered);
    }

    static String fingerprint(PublicKey key) throws Exception {
        Buffer.PlainBuffer buffer = new Buffer.PlainBuffer();
        String algorithm = key.getAlgorithm();
        if (algorithm != null && (algorithm.equalsIgnoreCase("Ed25519") ||
                algorithm.equalsIgnoreCase("EdDSA"))) {
            byte[] spki = key.getEncoded();
            if (spki == null ||
                    spki.length != ED25519_SPKI_PREFIX.length + 32 ||
                    !startsWith(spki, ED25519_SPKI_PREFIX))
                throw new IOException("지원하지 않는 Ed25519 호스트 공개키 형식");
            buffer.putString("ssh-ed25519");
            buffer.putString(Arrays.copyOfRange(
                    spki, ED25519_SPKI_PREFIX.length, spki.length));
        } else {
            // RSA and ECDSA use SSHJ's native wire-format implementation.
            KeyType.fromKey(key).putPubKeyIntoBuffer(key, buffer);
        }
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(buffer.getCompactData());
        return "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(hash);
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++)
            if (source[i] != prefix[i]) return false;
        return true;
    }
}
