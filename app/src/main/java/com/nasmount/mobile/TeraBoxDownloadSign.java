package com.nasmount.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Implements TeraBox's web-session download signature (RC4+Base64).
 * This is NOT a replacement for authorization. It signs the current ndus
 * session's sign1/sign3 supplied by /api/home/info on the SAME origin.
 */
final class TeraBoxDownloadSign {
    private TeraBoxDownloadSign() {}

    static String sign(String sign3, String sign1) throws IOException {
        if (sign3 == null || sign3.isEmpty() || sign1 == null || sign1.isEmpty() ||
                sign3.length() > 4096 || sign1.length() > 4096)
            throw new IOException("TeraBox 다운로드 서명 정보가 비어 있습니다.");
        byte[] seed = sign3.getBytes(StandardCharsets.UTF_8);
        byte[] content = sign1.getBytes(StandardCharsets.UTF_8);
        int[] state = new int[256];
        for (int i = 0; i < 256; i++) state[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + state[i] + (seed[i % seed.length] & 0xFF)) & 255;
            int temp = state[i]; state[i] = state[j]; state[j] = temp;
        }
        byte[] output = new byte[content.length];
        int i = 0;
        j = 0;
        for (int k = 0; k < content.length; k++) {
            i = (i + 1) & 255;
            j = (j + state[i]) & 255;
            int temp = state[i]; state[i] = state[j]; state[j] = temp;
            int stream = state[(state[i] + state[j]) & 255];
            output[k] = (byte) ((content[k] & 0xFF) ^ stream);
        }
        return Base64.getEncoder().encodeToString(output);
    }
}
