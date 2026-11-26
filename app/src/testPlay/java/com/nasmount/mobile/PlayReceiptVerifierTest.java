package com.nasmount.mobile;

import org.junit.Test;
import org.json.JSONObject;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class PlayReceiptVerifierTest {
    private static final String PACKAGE="com.filehubaio.mobile";
    @Test public void onlyAuthenticCompletedReceiptsUnlock() throws Exception {
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair key=gen.generateKeyPair();
        String publicKey=Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        PlayReceiptVerifier verifier=new PlayReceiptVerifier(publicKey);
        JSONObject receipt=new JSONObject().put("packageName",PACKAGE)
                .put("productId",MonetizationPolicy.PRODUCT_ID)
                .put("purchaseState",0).put("purchaseToken","unit-test-token");
        String raw=receipt.toString();
        String sig=sign(key,raw);
        assertTrue(verifier.configured());
        assertTrue(verifier.valid(raw,sig,PACKAGE));
        assertFalse(new PlayReceiptVerifier("").valid(raw,sig,PACKAGE));
        assertFalse(new PlayReceiptVerifier("bad").configured());
        assertFalse(verifier.valid(raw,sig,"com.nasmount.mobile"));
        assertFalse(verifier.valid(raw+" ",sig,PACKAGE));
        assertFalse(verifier.valid(raw,"invalid",PACKAGE));
        for(int state:new int[]{1,2,4}){
            String pending=receipt.put("purchaseState",state).toString();
            assertFalse(verifier.valid(pending,sign(key,pending),PACKAGE));
        }
        String wrong=receipt.put("purchaseState",0).put("productId","unrelated").toString();
        assertFalse(verifier.valid(wrong,sign(key,wrong),PACKAGE));
        String empty=receipt.put("productId",MonetizationPolicy.PRODUCT_ID)
                .put("purchaseToken","").toString();
        assertFalse(verifier.valid(empty,sign(key,empty),PACKAGE));
        String annual=receipt.put("productId",MonetizationPolicy.ANNUAL_PRODUCT_ID)
                .put("purchaseToken","annual-test-token").toString();
        String annualSig=sign(key,annual);
        assertTrue(verifier.validForProduct(annual,annualSig,PACKAGE,MonetizationPolicy.ANNUAL_PRODUCT_ID));
        assertFalse(verifier.valid(annual,annualSig,PACKAGE));
        assertFalse(verifier.validForProduct(raw,sig,PACKAGE,MonetizationPolicy.ANNUAL_PRODUCT_ID));
        assertFalse(SettingsBackup.transfersPreferenceFile("filehub_play_receipt"));
        assertFalse(SettingsBackup.transfersPreferenceFile("filehub_ad_usage"));
    }
    private static String sign(KeyPair key,String raw)throws Exception{
        Signature signer=Signature.getInstance("SHA1withRSA");
        signer.initSign(key.getPrivate());
        signer.update(raw.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
