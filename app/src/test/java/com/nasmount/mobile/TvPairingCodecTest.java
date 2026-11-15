package com.nasmount.mobile;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class TvPairingCodecTest {
    @Test public void encryptedLanTransferRoundTrip() throws Exception {
        byte[] key=TvPairingCodec.newKey();
        byte[] clear="TV pairing sample".getBytes(StandardCharsets.UTF_8);
        byte[] sealed=TvPairingCodec.encrypt(clear,key);
        assertFalse(new String(sealed,StandardCharsets.ISO_8859_1).contains("sample"));
        assertArrayEquals(clear,TvPairingCodec.decrypt(sealed,key));
        byte[] wrong=TvPairingCodec.newKey();
        assertThrows(Exception.class,()->TvPairingCodec.decrypt(sealed,wrong));
    }

    @Test public void privateLanQrContainsOnlyRandomKeyAndEndpoint() throws Exception {
        String uri=TvPairingCodec.pairingUri("192.168.0.22",49515,
                TvPairingCodec.newKey());
        TvPairingCodec.Destination destination=TvPairingCodec.parseUri(uri);
        assertEquals("192.168.0.22",destination.host);
        assertEquals(49515,destination.port);
        assertEquals(32,destination.key.length);
        assertFalse(uri.contains("password"));
        assertThrows(Exception.class,()->TvPairingCodec.parseUri(
                uri.replace("192.168.0.22","8.8.8.8")));
    }

    @Test public void accountsRoundTripWithoutTransferringDeviceTrust() throws Exception {
        Models.RemoteProfile p=new Models.RemoteProfile();
        p.name="Home NAS";p.protocol="SMB";p.host="192.168.0.9";
        p.username="demo";p.password="example-password";
        p.nasApiPassword="another-secret";p.nasDeviceId="private-phone-dsm-trust";
        byte[] serialized=TvPairingCodec.encodeProfiles(Collections.singletonList(p));
        assertFalse(new String(serialized,StandardCharsets.UTF_8)
                .contains("private-phone-dsm-trust"));
        List<Models.RemoteProfile> restored=TvPairingCodec.decodeProfiles(serialized);
        assertEquals(1,restored.size());
        assertEquals("example-password",restored.get(0).password);
        assertEquals("another-secret",restored.get(0).nasApiPassword);
        assertEquals("",restored.get(0).nasDeviceId);
        assertNotEquals(p.id,restored.get(0).id);
    }

    @Test public void emptySelectionAndUnknownProtocolAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                ()->TvPairingCodec.encodeProfiles(Collections.emptyList()));
        Models.RemoteProfile p=new Models.RemoteProfile();p.protocol="INVENTED";
        byte[] encoded=TvPairingCodec.encodeProfiles(Collections.singletonList(p));
        assertThrows(IllegalArgumentException.class,
                ()->TvPairingCodec.decodeProfiles(encoded));
    }
}
