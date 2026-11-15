package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class InternetQrTransferTest {
    @Test public void qrCarriesOnlyRelayAndRandomEncryptionMaterial() throws Exception {
        InternetQrTransfer.Session session=InternetQrTransfer.newSession(
                "https://relay.example.com");
        try {
            String qr=session.qrUri();
            assertTrue(qr.startsWith("https://relay.example.com/pair#"));
            assertFalse(qr.contains("password"));
            assertFalse(qr.contains(session.ownerToken));
            assertTrue(session.appUri().startsWith("filehubaio://relay?"));
            InternetQrTransfer.Invitation parsed=InternetQrTransfer.parseQr(qr);
            try {
                assertEquals("https://relay.example.com",parsed.endpoint);
                assertEquals(session.invitation.id,parsed.id);
                assertEquals(session.invitation.sendToken,parsed.sendToken);
                assertArrayEquals(session.invitation.key,parsed.key);
                assertEquals(session.invitation.shortCode(),parsed.shortCode());
                assertEquals(6,parsed.shortCode().length());
            }finally{parsed.clear();}
            InternetQrTransfer.Invitation direct=InternetQrTransfer.parseQr(
                    session.appUri());
            try{assertEquals(session.invitation.id,direct.id);}
            finally{direct.clear();}
        }finally{session.clear();}
    }

    @Test public void endpointRequiresHttpsWithoutCredentialsOrExtraPath() throws Exception {
        assertEquals("https://relay.example.com:443",
                InternetQrTransfer.validateEndpoint("https://relay.example.com:443/"));
        for(String invalid:new String[]{
                "http://relay.example.com","https://localhost","https://example.com/x",
                "https://user:pass@example.com","https://example.com?password=1",
                "https://example.com/#fragment","file:///etc/passwd"}) {
            assertThrows(Exception.class,()->InternetQrTransfer.validateEndpoint(invalid));
        }
    }

    @Test public void malformedQrAndDuplicateFieldsAreRejected() throws Exception {
        InternetQrTransfer.Session session=InternetQrTransfer.newSession(
                "https://relay.example.com");
        try {
            String valid=session.qrUri();
            assertThrows(Exception.class,()->InternetQrTransfer.parseQr(
                    session.appUri()+"&id="+session.invitation.id));
            assertThrows(Exception.class,()->InternetQrTransfer.parseQr(
                    session.appUri().replace("https%3A%2F%2F","http%3A%2F%2F")));
            assertThrows(Exception.class,()->InternetQrTransfer.parseQr(
                    session.appUri().replace("filehubaio://relay","filehubaio://pair")));
            assertThrows(Exception.class,()->InternetQrTransfer.parseQr(
                    session.appUri().replace("key=","key=bad")));
            assertThrows(Exception.class,()->InternetQrTransfer.parseQr(
                    valid.replace("relay.example.com/pair#","other.example.com/pair#")));
        }finally{session.clear();}
    }
}
