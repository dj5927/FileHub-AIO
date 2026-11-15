package com.nasmount.mobile;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Internet QR transfer. Only opaque AES-256-GCM ciphertext crosses the
 * HTTPS relay; the decryption key exists exclusively on the TV and phone.
 * Tokens are short-lived and sessions are one-use.
 */
final class InternetQrTransfer {
    static final long LIFETIME_MS = 120_000L;
    static final int MAX_PACKET = SettingsBackupCodec.MAX_FILE_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String QR_SCHEME = "filehubaio";
    private static final String QR_HOST = "relay";

    private InternetQrTransfer() {}

    static final class Invitation {
        final String endpoint, id, sendToken;
        final byte[] key;
        Invitation(String endpoint, String id, String sendToken, byte[] key) {
            this.endpoint=endpoint;
            this.id=id;
            this.sendToken=sendToken;
            this.key=key;
        }
        String shortCode() {return InternetQrTransfer.shortCode(id,key);}
        void clear(){Arrays.fill(key,(byte)0);}
    }

    static final class Session {
        final Invitation invitation;
        final String ownerToken;
        Session(Invitation invitation, String ownerToken) {
            this.invitation=invitation;
            this.ownerToken=ownerToken;
        }
        String appUri() {
            try {
                return QR_SCHEME+"://"+QR_HOST+"?server="+
                        URLEncoder.encode(invitation.endpoint,"UTF-8")+
                        "&id="+invitation.id+"&send="+invitation.sendToken+
                        "&key="+Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(invitation.key);
            }catch(Exception impossible){throw new IllegalStateException(impossible);}
        }
        /** A standard HTTPS QR for stock cameras; secrets remain in #fragment. */
        String qrUri() {
            try {
                return invitation.endpoint+"/pair#"+URLEncoder.encode(appUri(),"UTF-8");
            }catch(Exception impossible){throw new IllegalStateException(impossible);}
        }
        void clear(){invitation.clear();}
    }

    static String validateEndpoint(String value) throws Exception {
        if(value==null || value.trim().length()>240)
            throw new IllegalArgumentException("HTTPS 중계 서버 주소가 필요합니다.");
        URI uri=new URI(value.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost()==null || uri.getRawUserInfo()!=null ||
                uri.getRawQuery()!=null || uri.getRawFragment()!=null ||
                (uri.getRawPath()!=null && !uri.getRawPath().isEmpty() &&
                        !"/".equals(uri.getRawPath())) ||
                uri.getPort()>65535 || uri.getPort()==0 ||
                "localhost".equalsIgnoreCase(uri.getHost()))
            throw new IllegalArgumentException(
                    "공개 HTTPS 주소만 사용할 수 있습니다. 예: https://relay.example.com");
        return "https://"+uri.getRawAuthority().toLowerCase(Locale.ROOT);
    }

    private static String newToken(int bytes) {
        byte[] random=new byte[bytes];
        RANDOM.nextBytes(random);
        String result=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Arrays.fill(random,(byte)0);
        return result;
    }

    static Session newSession(String relayUrl) throws Exception {
        String endpoint=validateEndpoint(relayUrl);
        byte[] key=new byte[32];RANDOM.nextBytes(key);
        return new Session(new Invitation(endpoint,newToken(16),newToken(32),key),
                newToken(32));
    }

    static Invitation parseQr(String qr) throws Exception {
        URI uri=new URI(qr==null?"":qr.trim());
        String expectedEndpoint=null;
        if("https".equalsIgnoreCase(uri.getScheme()) &&
                "/pair".equals(uri.getRawPath()) && uri.getRawFragment()!=null) {
            expectedEndpoint=validateEndpoint("https://"+uri.getRawAuthority());
            uri=new URI(URLDecoder.decode(uri.getRawFragment(),"UTF-8"));
        }
        if(!QR_SCHEME.equalsIgnoreCase(uri.getScheme()) ||
                !QR_HOST.equalsIgnoreCase(uri.getHost()) ||
                uri.getRawQuery()==null || uri.getRawFragment()!=null)
            throw new IllegalArgumentException("FileHub 인터넷 QR이 아닙니다.");
        Map<String,String> args=new HashMap<>();
        for(String item:uri.getRawQuery().split("&")) {
            int sep=item.indexOf('=');
            if(sep<=0)throw new IllegalArgumentException("QR 형식 오류");
            String name=item.substring(0,sep);
            String value=URLDecoder.decode(item.substring(sep+1),"UTF-8");
            if(args.put(name,value)!=null)
                throw new IllegalArgumentException("중복 QR 필드");
        }
        if(args.size()!=4)throw new IllegalArgumentException("QR 필드 오류");
        String endpoint=validateEndpoint(args.get("server"));
        if(expectedEndpoint!=null && !expectedEndpoint.equals(endpoint))
            throw new IllegalArgumentException("QR 중계 서버 주소 불일치");
        String id=args.get("id");
        String sender=args.get("send");
        byte[] sessionId=decodeToken(id,16);
        byte[] senderId=decodeToken(sender,32);
        byte[] key=decodeToken(args.get("key"),32);
        Arrays.fill(sessionId,(byte)0);
        Arrays.fill(senderId,(byte)0);
        return new Invitation(endpoint,id,sender,key);
    }

    private static byte[] decodeToken(String token,int expectedBytes) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{"+(expectedBytes==16?22:43)+"}"))
            throw new IllegalArgumentException("QR 일회용 코드가 잘못됐습니다.");
        byte[] bytes=Base64.getUrlDecoder().decode(token);
        if(bytes.length!=expectedBytes ||
                !token.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
            throw new IllegalArgumentException("QR 일회용 코드가 잘못됐습니다.");
        return bytes;
    }

    static String shortCode(String id,byte[] key) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            md.update(id.getBytes(StandardCharsets.US_ASCII));
            byte[] hash=md.digest(key);
            int n=((hash[0]&255)<<16)|((hash[1]&255)<<8)|(hash[2]&255);
            return String.format(Locale.ROOT,"%06d",n%1_000_000);
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }

    private static HttpURLConnection open(String endpoint,String path,String method)
            throws Exception {
        HttpURLConnection connection=(HttpURLConnection)
                new URL(endpoint+path).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(9000);
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent","FileHub-AIO-Internet-QR");
        connection.setRequestProperty("Accept","application/octet-stream");
        return connection;
    }

    private static byte[] readLimited(InputStream input,int max) throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        byte[] chunk=new byte[16384];
        int count;
        while((count=input.read(chunk))!=-1) {
            if(buffer.size()+count>max)throw new java.io.IOException("전송 데이터 크기 초과");
            buffer.write(chunk,0,count);
        }
        return buffer.toByteArray();
    }

    static void create(Session session) throws Exception {
        HttpURLConnection c=open(session.invitation.endpoint,"/v1/sessions","POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        JSONObject json=new JSONObject();
        json.put("id",session.invitation.id);
        json.put("ownerToken",session.ownerToken);
        json.put("sendToken",session.invitation.sendToken);
        byte[] body=json.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try {
            try(OutputStream output=c.getOutputStream()){output.write(body);}
            if(c.getResponseCode()!=201)
                throw new java.io.IOException("중계 서버에서 QR 연결을 생성하지 못했습니다.");
        }finally{c.disconnect();Arrays.fill(body,(byte)0);}
    }

    /** Returns null while waiting; the relay deletes delivered packets. */
    static byte[] poll(Session session) throws Exception {
        HttpURLConnection c=open(session.invitation.endpoint,
                "/v1/sessions/"+session.invitation.id+"/payload","GET");
        c.setRequestProperty("Authorization","Bearer "+session.ownerToken);
        try {
            int status=c.getResponseCode();
            if(status==204)return null;
            if(status!=200)throw new java.io.IOException(
                    "수신 대기 시간이 만료됐거나 중계 서버 연결이 끊겼습니다.");
            byte[] encrypted=readLimited(c.getInputStream(),MAX_PACKET);
            try{return TvPairingCodec.decrypt(encrypted,session.invitation.key);}
            finally{Arrays.fill(encrypted,(byte)0);}
        }finally{c.disconnect();}
    }

    static void send(Invitation invitation,byte[] plaintext) throws Exception {
        if(plaintext==null || plaintext.length<2 ||
                plaintext.length>MAX_PACKET-128)
            throw new IllegalArgumentException("설정 데이터가 너무 큽니다.");
        byte[] encrypted=TvPairingCodec.encrypt(plaintext,invitation.key);
        HttpURLConnection c=open(invitation.endpoint,
                "/v1/sessions/"+invitation.id+"/payload","POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+invitation.sendToken);
        c.setRequestProperty("Content-Type","application/octet-stream");
        c.setFixedLengthStreamingMode(encrypted.length);
        try {
            try(OutputStream output=c.getOutputStream()){output.write(encrypted);}
            if(c.getResponseCode()!=202)
                throw new java.io.IOException(
                        "전송할 TV 연결이 없거나 QR이 만료됐습니다.");
        }finally{c.disconnect();Arrays.fill(encrypted,(byte)0);}
    }

    static void cancel(Session session) {
        HttpURLConnection c=null;
        try {
            c=open(session.invitation.endpoint,
                    "/v1/sessions/"+session.invitation.id,"DELETE");
            c.setRequestProperty("Authorization","Bearer "+session.ownerToken);
            c.getResponseCode();
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
    }
}
