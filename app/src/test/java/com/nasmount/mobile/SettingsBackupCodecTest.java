package com.nasmount.mobile;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SettingsBackupCodecTest {
    @Test public void portablePasswordBackupRoundTrip() throws Exception {
        byte[] input="private settings with password".getBytes(StandardCharsets.UTF_8);
        char[] password="strong-example-pass".toCharArray();
        byte[] file=SettingsBackupCodec.encrypt(input,password);
        assertArrayEquals(input,SettingsBackupCodec.decrypt(file,password));
        assertFalse(new String(file,StandardCharsets.ISO_8859_1).contains("settings"));
        assertEquals('F',file[0]);
    }

    @Test public void wrongPasswordOrTamperedBytesAreRejected() throws Exception {
        byte[] data=SettingsBackupCodec.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                "correct-pass".toCharArray());
        assertThrows(Exception.class,()->SettingsBackupCodec.decrypt(data,
                "wrong-password".toCharArray()));
        data[data.length-1] ^= 1;
        assertThrows(Exception.class,()->SettingsBackupCodec.decrypt(data,
                "correct-pass".toCharArray()));
    }

    @Test public void nonFileHubAndShortPasswordsRejected() throws Exception {
        byte[] file=SettingsBackupCodec.encrypt("hello".getBytes(StandardCharsets.UTF_8),
                "correct-pass".toCharArray());
        byte[] forged=Arrays.copyOf(file,file.length);
        forged[0]=0;
        assertThrows(IllegalArgumentException.class,()->SettingsBackupCodec.decrypt(
                forged,"correct-pass".toCharArray()));
        assertThrows(IllegalArgumentException.class,()->SettingsBackupCodec.encrypt(
                "hello".getBytes(StandardCharsets.UTF_8),"short".toCharArray()));
    }

    @Test public void oversizedBackupRejectedBeforePasswordWork() throws Exception {
        byte[] data=new byte[SettingsBackupCodec.MAX_PLAIN_BYTES+1];
        assertThrows(IllegalArgumentException.class,()->SettingsBackupCodec.encrypt(
                data,"correct-pass".toCharArray()));
    }
}
