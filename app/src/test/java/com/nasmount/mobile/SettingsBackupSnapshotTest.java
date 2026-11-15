package com.nasmount.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsBackupSnapshotTest {
    @Test public void nasProfilePreservesIdentityAndOmitsTrustToken() throws Exception {
        Models.RemoteProfile profile=new Models.RemoteProfile();
        profile.name="Family NAS";
        profile.protocol="WebDAV";
        profile.host="nas.example";
        profile.username="username";
        profile.password="secret";
        profile.nasApiPassword="admin-secret";
        profile.nasDeviceId="secret-phone-device-token";
        profile.nasShareRoots.add("/Movies");
        JSONObject json=SettingsBackup.profileToJson(profile);
        assertFalse(json.toString().contains("secret-phone-device-token"));
        Models.RemoteProfile restored=SettingsBackup.profileFromJson(json);
        assertEquals(profile.id,restored.id);
        assertEquals(profile.password,restored.password);
        assertEquals("",restored.nasDeviceId);
        assertEquals("/Movies",restored.nasShareRoots.get(0));
    }

    @Test public void backupRejectsUnknownFormatAndDuplicateProfiles() throws Exception {
        JSONObject root=new JSONObject();
        root.put("kind",SettingsBackup.KIND);
        root.put("version",SettingsBackup.VERSION);
        root.put("favorites",new JSONArray());
        root.put("preferences",new JSONObject());
        Models.RemoteProfile profile=new Models.RemoteProfile();
        JSONArray duplicates=new JSONArray().put(SettingsBackup.profileToJson(profile))
                .put(SettingsBackup.profileToJson(profile));
        root.put("remotes",duplicates);
        assertThrows(IllegalArgumentException.class,()->SettingsBackup.validate(root));
        root.put("remotes",new JSONArray());
        root.put("kind","not-filehub");
        assertThrows(IllegalArgumentException.class,()->SettingsBackup.validate(root));
    }

    @Test public void playbackAndReadingStateNeverBelongToPortableSettings() {
        for (String name : new String[]{
                "video_watch_progress", "nasmount_reading_history_v1",
                "comic_reader_progress", "text_book_reader_progress",
                "large_txt_read_positions", "comic_logical_page_migration",
                "video_subtitle_inventory"
        }) {
            assertTrue("Expected device-only state: " + name,
                    SettingsBackup.isDeviceOnlyState(name));
            assertFalse("Must not export/import: " + name,
                    SettingsBackup.transfersPreferenceFile(name));
        }
    }

    @Test public void connectionsAndAppearanceRemainPortable() {
        for (String name : new String[]{
                "appearance", "file_view", "nas_video_settings",
                "nasmount_reader_folders", "comic_reader_preferences",
                "epub_book_settings", "text_book_reader_settings",
                "folder_reader_playback_overrides_v1"
        }) {
            assertTrue("Expected portable setting: " + name,
                    SettingsBackup.transfersPreferenceFile(name));
            assertFalse("Not device-only state: " + name,
                    SettingsBackup.isDeviceOnlyState(name));
        }
    }

    @Test public void olderBackupCanContainDeviceHistoryWithoutRequiringItsImport() throws Exception {
        JSONObject backup=new JSONObject();
        backup.put("kind",SettingsBackup.KIND);
        backup.put("version",SettingsBackup.VERSION);
        backup.put("remotes",new JSONArray());
        backup.put("favorites",new JSONArray());
        JSONObject oldHistory=new JSONObject().put("sample.position",
                new JSONObject().put("t","l").put("v",123L));
        backup.put("preferences",new JSONObject()
                .put("video_watch_progress",oldHistory));
        SettingsBackup.validate(backup);
        assertFalse(SettingsBackup.transfersPreferenceFile("video_watch_progress"));
    }

    @Test public void typedSettingsAreValidatedBeforeImport() throws Exception {
        JSONObject backup=new JSONObject();
        backup.put("kind",SettingsBackup.KIND);
        backup.put("version",SettingsBackup.VERSION);
        backup.put("remotes",new JSONArray());
        backup.put("favorites",new JSONArray());
        JSONObject entries=new JSONObject();
        entries.put("hidden",new JSONObject().put("t","b").put("v",true));
        backup.put("preferences",new JSONObject().put("file_view",entries));
        SettingsBackup.validate(backup);
        entries.put("broken",new JSONObject().put("t","evil").put("v","bad"));
        assertThrows(IllegalArgumentException.class,()->SettingsBackup.validate(backup));
    }
}
