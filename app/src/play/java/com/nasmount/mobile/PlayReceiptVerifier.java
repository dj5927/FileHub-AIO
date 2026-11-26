package com.nasmount.mobile;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;

/** Fail closed on missing key, invalid signature, wrong package/product or pending receipt. */
final class PlayReceiptVerifier {
    private final PublicKey key;
    PlayReceiptVerifier(String publicKey){
        PublicKey parsed=null;
        try{parsed=KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));}
        catch(Exception ignored){}
        key=parsed;
    }
    boolean configured(){return key!=null;}
    boolean valid(String json,String signature,String packageName){
        return validForProduct(json,signature,packageName,MonetizationPolicy.PRODUCT_ID);
    }
    boolean validForProduct(String json,String signature,String packageName,String product){
        if(!MonetizationPolicy.premiumProduct(product)||key==null||json==null||signature==null||json.length()>65536||signature.length()>4096)return false;
        try{
            Signature verifier=Signature.getInstance("SHA1withRSA");
            verifier.initVerify(key);
            verifier.update(json.getBytes(StandardCharsets.UTF_8));
            if(!verifier.verify(Base64.getDecoder().decode(signature)))return false;
            JSONObject data=new JSONObject(json);
            if(!packageName.equals(data.optString("packageName"))||
                    data.optInt("purchaseState",-1)!=0||
                    data.optString("purchaseToken").isEmpty())return false;
            if(product.equals(data.optString("productId")))return true;
            JSONArray products=data.optJSONArray("productIds");
            if(products==null)products=data.optJSONArray("products");
            return products!=null&&products.length()==1&&product.equals(products.optString(0));
        }catch(Exception ignored){return false;}
    }
}
