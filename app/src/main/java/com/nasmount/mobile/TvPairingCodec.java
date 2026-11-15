package com.nasmount.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One-use LAN pairing. The QR contains a random key, NEVER saved passwords. */
final class TvPairingCodec {
    static final int MAX_PROFILES = 12;
    static final int MAX_PACKET = SettingsBackupCodec.MAX_FILE_BYTES;
    static final long LIFETIME_MS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Destination {
        final String host;
        final int port;
        final byte[] key;
        Destination(String host, int port, byte[] key) {
            this.host=host; this.port=port; this.key=key;
        }
    }

    static byte[] newKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    static String pairingUri(String host, int port, byte[] key) {
        return "filehubaio://pair?host=" + host + "&port=" + port +
                "&key=" + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    static Destination parseUri(String value) throws Exception {
        URI uri = new URI(value.trim());
        if (!"filehubaio".equalsIgnoreCase(uri.getScheme()) ||
                !"pair".equalsIgnoreCase(uri.getHost()) || uri.getRawQuery()==null)
            throw new IllegalArgumentException("FileHub TV QR 주소가 아닙니다.");
        String host="",port="",token="";
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq<=0) continue;
            String key=pair.substring(0,eq), val=pair.substring(eq+1);
            if ("host".equals(key)) host=val;
            if ("port".equals(key)) port=val;
            if ("key".equals(key)) token=val;
        }
        if (!host.matches("[0-9.]{7,15}"))
            throw new IllegalArgumentException("TV의 사설 IPv4 주소가 필요합니다.");
        InetAddress address = InetAddress.getByName(host);
        if (!address.isSiteLocalAddress() || address.getAddress().length != 4)
            throw new IllegalArgumentException("같은 Wi-Fi의 사설 IP만 사용할 수 있습니다.");
        int endpoint = Integer.parseInt(port);
        if (endpoint<1024 || endpoint>65535) throw new IllegalArgumentException("TV 포트 오류");
        byte[] secret=Base64.getUrlDecoder().decode(token);
        if (secret.length!=32) throw new IllegalArgumentException("TV 연결 코드가 잘못됐습니다.");
        return new Destination(host,endpoint,secret);
    }

    static byte[] encrypt(byte[] plain, byte[] key) throws Exception {
        if (plain.length>MAX_PACKET-128 || key.length!=32)
            throw new IllegalArgumentException("전송 데이터가 너무 큽니다.");
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),
                new GCMParameterSpec(128,nonce));
        byte[] sealed=cipher.doFinal(plain);
        byte[] result=new byte[nonce.length+sealed.length];
        System.arraycopy(nonce,0,result,0,nonce.length);
        System.arraycopy(sealed,0,result,nonce.length,sealed.length);
        return result;
    }

    static byte[] decrypt(byte[] packet, byte[] key) throws Exception {
        if (packet.length<29 || packet.length>MAX_PACKET || key.length!=32)
            throw new IllegalArgumentException("잘못된 암호화 패킷");
        byte[] nonce=java.util.Arrays.copyOfRange(packet,0,12);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),
                new GCMParameterSpec(128,nonce));
        return cipher.doFinal(packet,12,packet.length-12);
    }

    static byte[] encodeProfiles(List<Models.RemoteProfile> selected) throws Exception {
        if (selected.isEmpty() || selected.size()>MAX_PROFILES)
            throw new IllegalArgumentException("1~12개 연결만 보낼 수 있습니다.");
        JSONObject payload=new JSONObject();
        payload.put("kind","filehub-tv-profiles-v1");
        JSONArray items=new JSONArray();
        for (Models.RemoteProfile p:selected) {
            JSONObject o=new JSONObject();
            o.put("name",p.name);o.put("protocol",p.protocol);
            o.put("host",p.host);o.put("port",p.port);
            o.put("root",p.root);o.put("username",p.username);
            o.put("password",p.password);o.put("ssl",p.ssl);
            o.put("fingerprint",p.fingerprint);
            o.put("nasVendor",p.nasVendor);o.put("nasApiBase",p.nasApiBase);
            o.put("nasApiUser",p.nasApiUser);o.put("nasApiPassword",p.nasApiPassword);
            o.put("nasShareRoot",p.nasShareRoot);
            o.put("nasShareRoots",new JSONArray(p.nasShareRoots));
            o.put("nasPublicHost",p.nasPublicHost);
            o.put("qnapCloudSmartUrl",p.qnapCloudSmartUrl);
            // Synology trusted-device IDs are deliberately NOT transferred.
            items.put(o);
        }
        payload.put("items",items);
        byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_PACKET-128)throw new IllegalArgumentException("전송 정보가 너무 큽니다.");
        return bytes;
    }

    static List<Models.RemoteProfile> decodeProfiles(byte[] plain) throws Exception {
        JSONObject payload=new JSONObject(new String(plain,StandardCharsets.UTF_8));
        if (!"filehub-tv-profiles-v1".equals(payload.optString("kind")))
            throw new IllegalArgumentException("지원하지 않는 연결 형식입니다.");
        JSONArray items=payload.getJSONArray("items");
        if (items.length()<1 || items.length()>MAX_PROFILES)
            throw new IllegalArgumentException("연결 개수 오류");
        List<Models.RemoteProfile> profiles=new ArrayList<>();
        for(int i=0;i<items.length();i++) {
            JSONObject o=items.getJSONObject(i);
            Models.RemoteProfile p=new Models.RemoteProfile();
            p.id=UUID.randomUUID().toString();
            p.name=o.optString("name","");p.protocol=o.getString("protocol");
            if (!(p.protocol.equals("SMB") || p.protocol.equals("FTP") ||
                    p.protocol.equals("SFTP") || p.protocol.equals("WebDAV") ||
                    p.protocol.equals("MYBOX") || p.protocol.equals("TeraBox")))
                throw new IllegalArgumentException("지원하지 않는 연결 유형입니다.");
            p.host=o.optString("host","");p.port=o.optInt("port",0);
            p.root=o.optString("root","");p.username=o.optString("username","");
            p.password=o.optString("password","");p.ssl=o.optBoolean("ssl",true);
            p.fingerprint=o.optString("fingerprint","");
            p.nasVendor=o.optString("nasVendor","");
            p.nasApiBase=o.optString("nasApiBase","");
            p.nasApiUser=o.optString("nasApiUser","");
            p.nasApiPassword=o.optString("nasApiPassword","");
            p.nasShareRoot=o.optString("nasShareRoot","");
            JSONArray roots=o.optJSONArray("nasShareRoots");
            if(roots!=null)for(int j=0;j<roots.length();j++)
                p.nasShareRoots.add(roots.optString(j));
            p.nasPublicHost=o.optString("nasPublicHost","");
            p.qnapCloudSmartUrl=o.optString("qnapCloudSmartUrl","");
            profiles.add(p);
        }
        return profiles;
    }
}
