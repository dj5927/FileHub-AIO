package com.nasmount.mobile;

import android.os.Build;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.KeyAgreement;

/**
 * Zero-QR, same-LAN discovery for the opt-in Export / Import screens.
 * ECDH prevents broadcast observers from learning the AES session key.
 * This is not a certified peer identity: use on trusted private Wi-Fi only.
 */
final class LanSettingsTransfer {
    static final int UDP_PORT=38427;
    static final long WINDOW_MS=120_000L;
    private static final String PROBE="FILEHUB_SETTINGS_DISCOVER_V1";
    private static final String OFFER="FILEHUB_SETTINGS_OFFER_V1";
    private static final String TRANSFER="FILEHUB_SETTINGS_TCP_V1";
    private static final byte[] CONTEXT=TRANSFER.getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM=new SecureRandom();
    private LanSettingsTransfer(){}

    interface OnReceived {void receive(JSONObject snapshot);}
    interface OnFound {void found(List<Offer> available);}
    interface OnStopped {void stopped();}

    static final class Offer {
        final String ip,name;
        final int port;
        final byte[] challenge;
        final PublicKey receiverPublic;
        Offer(String ip,String name,int port,byte[] challenge,PublicKey receiverPublic){
            this.ip=ip;this.name=name;this.port=port;
            this.challenge=challenge;this.receiverPublic=receiverPublic;
        }
        String label(){return name+"  ·  "+ip;}
    }

    static KeyPair generateKeys() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"),RANDOM);
        return generator.generateKeyPair();
    }

    static byte[] sessionKey(PrivateKey mine,PublicKey remote,byte[] challenge)
            throws Exception {
        if(challenge==null || challenge.length!=16)
            throw new IllegalArgumentException("LAN 페어링 토큰이 잘못됐습니다.");
        KeyAgreement exchange=KeyAgreement.getInstance("ECDH");
        exchange.init(mine);
        exchange.doPhase(remote,true);
        byte[] shared=exchange.generateSecret();
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        hash.update(CONTEXT);
        hash.update(challenge);
        byte[] derived=hash.digest(shared);
        java.util.Arrays.fill(shared,(byte)0);
        return derived;
    }

    static String encodeOffer(int port,byte[] challenge,PublicKey key,String name) {
        if(port<1024 || port>65535 || challenge.length!=16)
            throw new IllegalArgumentException("Invalid offer");
        String friendly=Base64.getUrlEncoder().withoutPadding().encodeToString(
                name.getBytes(StandardCharsets.UTF_8));
        return OFFER+"|"+port+"|"+
                Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)+"|"+
                Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded())+"|"+
                friendly;
    }

    static Offer parseOffer(String message,InetAddress source) throws Exception {
        String[] fields=message.split("\\|",-1);
        if(fields.length!=5 || !OFFER.equals(fields[0]) ||
                !(source instanceof Inet4Address) || !source.isSiteLocalAddress())
            throw new IllegalArgumentException("Not a FileHub LAN receiver");
        int port=Integer.parseInt(fields[1]);
        if(port<1024 || port>65535)throw new IllegalArgumentException("Bad port");
        byte[] nonce=Base64.getUrlDecoder().decode(fields[2]);
        if(nonce.length!=16)throw new IllegalArgumentException("Bad session nonce");
        byte[] encoded=Base64.getUrlDecoder().decode(fields[3]);
        if(encoded.length>256 || encoded.length<64)
            throw new IllegalArgumentException("Bad public key");
        PublicKey publicKey=KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(encoded));
        String label=new String(Base64.getUrlDecoder().decode(fields[4]),
                StandardCharsets.UTF_8);
        if(label.length()>50)label=label.substring(0,50);
        return new Offer(source.getHostAddress(),label,port,nonce,publicKey);
    }

    static final class Receiver implements AutoCloseable {
        private final KeyPair identity;
        private final byte[] challenge=new byte[16];
        private final ServerSocket tcp;
        private final DatagramSocket udp;
        private final OnReceived callback;
        private final OnStopped expiry;
        private final AtomicBoolean used=new AtomicBoolean(false);
        private volatile boolean closed;
        private final String advertisement;

        Receiver(OnReceived callback,OnStopped expiry) throws Exception {
            this.callback=callback;this.expiry=expiry;
            identity=generateKeys();RANDOM.nextBytes(challenge);
            tcp=new ServerSocket(0,4);
            tcp.setSoTimeout(1100);
            DatagramSocket listener=new DatagramSocket(null);
            try {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(UDP_PORT));
                listener.setSoTimeout(1100);
            }catch(Exception failure){listener.close();tcp.close();throw failure;}
            udp=listener;
            advertisement=encodeOffer(tcp.getLocalPort(),challenge,
                    identity.getPublic(),"FileHub · "+Build.MODEL);
        }

        void start() {
            long deadline=System.currentTimeMillis()+WINDOW_MS;
            Thread beacons=new Thread(()->{
                byte[] data=advertisement.getBytes(StandardCharsets.UTF_8);
                byte[] buffer=new byte[256];
                while(!closed && !used.get() && System.currentTimeMillis()<deadline){
                    try {
                        DatagramPacket request=new DatagramPacket(buffer,buffer.length);
                        udp.receive(request);
                        String query=new String(request.getData(),request.getOffset(),
                                request.getLength(),StandardCharsets.US_ASCII);
                        if(!PROBE.equals(query) || !request.getAddress().isSiteLocalAddress())
                            continue;
                        udp.send(new DatagramPacket(data,data.length,
                                request.getAddress(),request.getPort()));
                    }catch(SocketTimeoutException ignored){}
                    catch(Exception ignored){if(closed)break;}
                }
            },"filehub-lan-offer");
            beacons.setDaemon(true);
            beacons.start();
            Thread accepting=new Thread(()->{
                int invalid=0;
                while(!closed && !used.get() && invalid<20 &&
                        System.currentTimeMillis()<deadline){
                    try(Socket client=tcp.accept()){
                        client.setSoTimeout(10_000);
                        if(!client.getInetAddress().isSiteLocalAddress())continue;
                        if(receiveClient(client))break;
                        invalid++;
                    }catch(SocketTimeoutException ignored){}
                    catch(Exception ignored){invalid++;}
                }
                close();
                expiry.stopped();
            },"filehub-lan-import");
            accepting.setDaemon(true);
            accepting.start();
        }

        private boolean receiveClient(Socket client) throws Exception {
            DataInputStream input=new DataInputStream(client.getInputStream());
            if(!TRANSFER.equals(input.readUTF()))return false;
            int publicLength=input.readUnsignedShort();
            if(publicLength<64 || publicLength>256)return false;
            byte[] encoded=new byte[publicLength];input.readFully(encoded);
            PublicKey remote=KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            int count=input.readInt();
            if(count<29 || count>TvPairingCodec.MAX_PACKET)return false;
            byte[] sealed=new byte[count];input.readFully(sealed);
            byte[] secret=sessionKey(identity.getPrivate(),remote,challenge);
            byte[] plaintext;
            try{plaintext=TvPairingCodec.decrypt(sealed,secret);}
            finally{java.util.Arrays.fill(secret,(byte)0);}
            JSONObject settings;
            try {
                settings=new JSONObject(new String(plaintext,StandardCharsets.UTF_8));
                SettingsBackup.validate(settings);
            }finally{java.util.Arrays.fill(plaintext,(byte)0);}
            if(!used.compareAndSet(false,true))return false;
            DataOutputStream output=new DataOutputStream(client.getOutputStream());
            output.writeUTF("OK");output.flush();
            callback.receive(settings);
            return true;
        }

        @Override public void close(){
            closed=true;
            udp.close();
            try{tcp.close();}catch(Exception ignored){}
            java.util.Arrays.fill(challenge,(byte)0);
        }
    }

    static final class Discovery implements AutoCloseable {
        private volatile boolean cancelled;
        private volatile DatagramSocket socket;
        private final OnFound callback;
        Discovery(OnFound callback){this.callback=callback;}
        void start() {
            Thread thread=new Thread(()->{
                Map<String,Offer> candidates=new LinkedHashMap<>();
                long started=System.currentTimeMillis();
                long firstSeen=0;
                try(DatagramSocket udp=new DatagramSocket()) {
                    socket=udp;
                    udp.setBroadcast(true);udp.setSoTimeout(350);
                    byte[] query=PROBE.getBytes(StandardCharsets.US_ASCII);
                    byte[] response=new byte[1024];
                    long lastProbe=0;
                    while(!cancelled && System.currentTimeMillis()-started<WINDOW_MS) {
                        long now=System.currentTimeMillis();
                        if(now-lastProbe>=1200) {
                            for(InetAddress broadcast:broadcastAddresses()) {
                                try{udp.send(new DatagramPacket(query,query.length,
                                        broadcast,UDP_PORT));}
                                catch(Exception ignored){}
                            }
                            lastProbe=now;
                        }
                        try {
                            DatagramPacket packet=new DatagramPacket(response,response.length);
                            udp.receive(packet);
                            Offer offer=parseOffer(new String(packet.getData(),
                                    packet.getOffset(),packet.getLength(),
                                    StandardCharsets.UTF_8),packet.getAddress());
                            candidates.put(offer.ip+":"+offer.port,offer);
                            if(firstSeen==0)firstSeen=System.currentTimeMillis();
                        }catch(SocketTimeoutException ignored){}
                        catch(Exception ignored){}
                        if(firstSeen>0 && System.currentTimeMillis()-firstSeen>=1700)
                            break;
                    }
                }catch(Exception ignored){}
                finally{socket=null;}
                if(!cancelled)callback.found(new ArrayList<>(candidates.values()));
            },"filehub-lan-discover");
            thread.setDaemon(true);thread.start();
        }

        private static List<InetAddress> broadcastAddresses() {
            List<InetAddress> targets=new ArrayList<>();
            try{targets.add(InetAddress.getByName("255.255.255.255"));}
            catch(Exception ignored){}
            try {
                java.util.Enumeration<NetworkInterface> interfaces=
                        NetworkInterface.getNetworkInterfaces();
                while(interfaces!=null && interfaces.hasMoreElements()){
                    NetworkInterface network=interfaces.nextElement();
                    if(!network.isUp() || network.isLoopback() || network.isVirtual())continue;
                    for(InterfaceAddress address:network.getInterfaceAddresses()){
                        InetAddress broadcast=address.getBroadcast();
                        if(broadcast!=null && !targets.contains(broadcast))targets.add(broadcast);
                    }
                }
            }catch(Exception ignored){}
            return targets;
        }

        @Override public void close(){
            cancelled=true;
            DatagramSocket current=socket;
            if(current!=null)current.close();
        }
    }

    static void send(Offer target,byte[] payload) throws Exception {
        if(payload==null || payload.length<=0 ||
                payload.length>TvPairingCodec.MAX_PACKET-128)
            throw new IllegalArgumentException("설정 전송 용량을 초과했습니다.");
        KeyPair temporary=generateKeys();
        byte[] secret=sessionKey(temporary.getPrivate(),target.receiverPublic,
                target.challenge);
        byte[] ciphertext;
        try{ciphertext=TvPairingCodec.encrypt(payload,secret);}
        finally{java.util.Arrays.fill(secret,(byte)0);}
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(target.ip,target.port),6000);
            socket.setSoTimeout(12_000);
            DataOutputStream output=new DataOutputStream(socket.getOutputStream());
            output.writeUTF(TRANSFER);
            byte[] myPublic=temporary.getPublic().getEncoded();
            output.writeShort(myPublic.length);output.write(myPublic);
            output.writeInt(ciphertext.length);output.write(ciphertext);output.flush();
            DataInputStream input=new DataInputStream(socket.getInputStream());
            if(!"OK".equals(input.readUTF()))throw new IOException("TV가 설정을 받지 못했습니다.");
        }finally{java.util.Arrays.fill(ciphertext,(byte)0);}
    }
}
