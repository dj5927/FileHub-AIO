package com.nasmount.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.*;

/** Real Windows-generated ciphertext, decrypted by Android's unchanged codec. */
public class WindowsPortableBackupInteropTest {
    private static final String PASSWORD = "Example-passphrase-123!";

    @Test public void windowsBackupDecryptsOnAndroidAndPreservesAdvancedWebDav()
            throws Exception {
        byte[] source;
        try (InputStream in = getClass().getResourceAsStream(
                "/pc-v128-portable-settings.fhbackup")) {
            assertNotNull("Windows interop fixture missing", in);
            source = in.readAllBytes();
        }
        byte[] json = SettingsBackupCodec.decrypt(source, PASSWORD.toCharArray());
        JSONObject snapshot = new JSONObject(
                new String(json, StandardCharsets.UTF_8));
        SettingsBackup.validate(snapshot);
        JSONArray profiles = snapshot.getJSONArray("remotes");
        assertEquals(1, profiles.length());
        Models.RemoteProfile restored =
                SettingsBackup.profileFromJson(profiles.getJSONObject(0));
        assertEquals("WebDAV", restored.protocol);
        assertEquals("example.invalid", restored.host);
        assertEquals("/demo", restored.root);
        assertEquals("not-a-real-credential", restored.password);
        assertTrue(restored.useLockBridge);
        assertFalse(restored.useNativeWebDavLocks); // obsolete option is ignored
        assertFalse(restored.readOnly);
    }

    @Test public void androidBackupMayBeDecodedByWindowsWithoutGoogleProfiles()
            throws Exception {
        Models.RemoteProfile remote = new Models.RemoteProfile();
        remote.id = "11111111-2222-3333-4444-555555555555";
        remote.protocol = "FTP";
        remote.name = "Android FTPS test";
        remote.host = "ftp.example.invalid";
        remote.port = 990;
        remote.username = "fake-user";
        remote.password = "not-real-ftp-password";
        remote.root = "/demo";
        remote.ftpImplicitTls = true;
        remote.ftpExplicitTls = false;
        remote.ftpCharset = "CP949 / EUC-KR (한국어)";
        JSONObject snapshot = new JSONObject();
        snapshot.put("kind", SettingsBackup.KIND);
        snapshot.put("version", SettingsBackup.VERSION);
        snapshot.put("remotes", new JSONArray()
                .put(SettingsBackup.profileToJson(remote)));
        snapshot.put("favorites", new JSONArray());
        snapshot.put("preferences", new JSONObject());
        SettingsBackup.validate(snapshot);
        byte[] encrypted = SettingsBackupCodec.encrypt(snapshot.toString()
                .getBytes(StandardCharsets.UTF_8), PASSWORD.toCharArray());
        assertArrayEquals(snapshot.toString().getBytes(StandardCharsets.UTF_8),
                SettingsBackupCodec.decrypt(encrypted, PASSWORD.toCharArray()));
        String output = System.getenv("FILEHUB_ANDROID_PORTABLE_TEST_FIXTURE");
        if (output != null && !output.trim().isEmpty())
            Files.write(Paths.get(output), encrypted);
    }
}
