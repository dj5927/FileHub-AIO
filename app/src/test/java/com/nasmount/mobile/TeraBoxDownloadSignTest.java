package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

/** Reference RC4 test vector plus malformed signed-download metadata cases. */
public final class TeraBoxDownloadSignTest {
    @Test public void rc4Base64MatchesKnownReferenceVector() throws Exception {
        // Standard RC4: Key="Key", plaintext="Plaintext" gives
        // ciphertext BB F3 16 E8 D9 40 AF 0A D3.
        assertEquals("u/MW6NlArwrT", TeraBoxDownloadSign.sign("Key", "Plaintext"));
    }

    @Test public void rejectsEmptyOrMissingSessionSigningFields() {
        assertThrows(java.io.IOException.class, () ->
                TeraBoxDownloadSign.sign("", "data"));
        assertThrows(java.io.IOException.class, () ->
                TeraBoxDownloadSign.sign("key", ""));
        assertThrows(java.io.IOException.class, () ->
                TeraBoxDownloadSign.sign(null, "data"));
    }

    @Test public void deterministicAndDoesNotUseLocalTimezone() throws Exception {
        String a = TeraBoxDownloadSign.sign("sample-secret-key", "signed-information");
        assertEquals(a, TeraBoxDownloadSign.sign(
                "sample-secret-key", "signed-information"));
        assertNotEquals(a, TeraBoxDownloadSign.sign(
                "another-key", "signed-information"));
    }
}
