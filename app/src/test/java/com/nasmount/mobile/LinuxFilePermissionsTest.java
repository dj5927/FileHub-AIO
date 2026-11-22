package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class LinuxFilePermissionsTest {
    @Test public void readWriteExecuteBitsUseCorrectPosixOctal() {
        assertEquals(0755, LinuxFilePermissions.parseOctal("755"));
        assertEquals(0644, LinuxFilePermissions.parseOctal("0644"));
        assertEquals("0755", LinuxFilePermissions.octal(0755));
        assertEquals("rwxr-xr-x", LinuxFilePermissions.symbolic(0755));
        assertEquals("rw-r--r--", LinuxFilePermissions.symbolic(0644));
        assertEquals("---------", LinuxFilePermissions.symbolic(0000));
    }

    @Test public void groupAndOtherChecksDoNotAlterSpecialBits() {
        boolean[] flags = {true, true, true, true, false, true,
                true, false, true};
        assertEquals(04755, LinuxFilePermissions.withRwx(04000, flags));
        assertEquals("rwsr-xr-x", LinuxFilePermissions.symbolic(04755));
        assertEquals("rwxr-sr-t", LinuxFilePermissions.symbolic(03755));
        assertEquals("rwSr-Sr-T", LinuxFilePermissions.symbolic(07644));
    }

    @Test public void invalidModeOrOutOfRangeIsRejected() {
        for (String input : new String[]{"", "8", "888", "77", "01234",
                "-755", "0x1ff", "0777;rm", "77777", "  "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> LinuxFilePermissions.parseOctal(input));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LinuxFilePermissions.checkedMode(010000));
        assertThrows(IllegalArgumentException.class,
                () -> LinuxFilePermissions.withRwx(0755, new boolean[8]));
    }

    @Test public void sftpImplicitHomeDoesNotRemoveRootNavigation() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "SFTP";
        p.root = "";
        assertTrue(RemoteProvider.sftpUsesLoginHome(p));
        // An explicitly selected root is not redirected to login home.
        p.root = "/";
        assertFalse(RemoteProvider.sftpUsesLoginHome(p));
        p.root = "/some/explicit/subdirectory";
        assertFalse(RemoteProvider.sftpUsesLoginHome(p));
        p.protocol = "FTP";
        p.root = "";
        assertFalse(RemoteProvider.sftpUsesLoginHome(p));
        assertEquals("/home", RemoteProvider.parent("/home/deck"));
        assertEquals("/", RemoteProvider.parent("/home"));
    }

    @Test public void readOnlySftpRejectsChmodBeforeNetworkConnection() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "SFTP";
        p.readOnly = true;
        assertThrows(RemoteProvider.SftpPermissionFailure.class, () ->
                RemoteProvider.sftpChmod(p, "/home/deck/a.txt", 0644, 0755));
    }
}
