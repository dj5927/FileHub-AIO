package com.nasmount.mobile;

import org.junit.Test;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import static org.junit.Assert.*;

public class LanSettingsTransferTest {
    @Test public void twoPeersDeriveIdenticalEcdhSessionSecret() throws Exception {
        KeyPair phone=LanSettingsTransfer.generateKeys();
        KeyPair television=LanSettingsTransfer.generateKeys();
        byte[] nonce=new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        byte[] sender=LanSettingsTransfer.sessionKey(phone.getPrivate(),
                television.getPublic(),nonce);
        byte[] receiver=LanSettingsTransfer.sessionKey(television.getPrivate(),
                phone.getPublic(),nonce);
        assertArrayEquals(sender,receiver);
        byte[] data="NAS settings".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data,TvPairingCodec.decrypt(TvPairingCodec.encrypt(data,sender),receiver));
    }

    @Test public void discoveryOfferRoundTripsLocalNameAddressAndPort() throws Exception {
        KeyPair receiver=LanSettingsTransfer.generateKeys();
        byte[] nonce=new byte[16];
        String advertisement=LanSettingsTransfer.encodeOffer(
                42000,nonce,receiver.getPublic(),"Living room TV");
        LanSettingsTransfer.Offer offer=LanSettingsTransfer.parseOffer(advertisement,
                InetAddress.getByName("192.168.1.12"));
        assertEquals("Living room TV",offer.name);
        assertEquals("192.168.1.12",offer.ip);
        assertEquals(42000,offer.port);
        assertArrayEquals(receiver.getPublic().getEncoded(),offer.receiverPublic.getEncoded());
    }

    @Test public void publicInternetOffersAndBadPortsAreRejected() throws Exception {
        KeyPair pair=LanSettingsTransfer.generateKeys();
        byte[] nonce=new byte[16];
        String valid=LanSettingsTransfer.encodeOffer(42000,nonce,pair.getPublic(),"TV");
        assertThrows(Exception.class,()->LanSettingsTransfer.parseOffer(
                valid,InetAddress.getByName("8.8.8.8")));
        assertThrows(Exception.class,()->LanSettingsTransfer.parseOffer(
                valid.replace("|42000|","|99|"),
                InetAddress.getByName("192.168.1.50")));
        assertThrows(Exception.class,()->LanSettingsTransfer.parseOffer(
                "OTHER_APP"+valid,InetAddress.getByName("192.168.1.50")));
    }

    @Test public void distinctSessionNonceProducesDifferentEncryptionKey() throws Exception {
        KeyPair phone=LanSettingsTransfer.generateKeys();
        KeyPair tv=LanSettingsTransfer.generateKeys();
        byte[] a=new byte[16];
        byte[] b=new byte[16];b[0]=1;
        assertFalse(java.util.Arrays.equals(
                LanSettingsTransfer.sessionKey(phone.getPrivate(),tv.getPublic(),a),
                LanSettingsTransfer.sessionKey(phone.getPrivate(),tv.getPublic(),b)));
        assertThrows(IllegalArgumentException.class,()->LanSettingsTransfer.sessionKey(
                phone.getPrivate(),tv.getPublic(),new byte[4]));
    }
}
