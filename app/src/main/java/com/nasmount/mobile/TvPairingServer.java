package com.nasmount.mobile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary, one-use encrypted LAN receiver. Nothing is persisted until TV approval. */
final class TvPairingServer implements AutoCloseable {
    interface Receiver { void received(byte[] decodedPayload); }
    private final ServerSocket socket;
    private final byte[] key;
    private final long expiresAt;
    private final Receiver receiver;
    private final AtomicBoolean accepted = new AtomicBoolean(false);
    private volatile boolean stopped;
    private Thread thread;
    final String uri;

    TvPairingServer(Receiver receiver) throws Exception {
        this.receiver=receiver;
        key=TvPairingCodec.newKey();
        socket=new ServerSocket(0,3);
        socket.setSoTimeout(1000);
        expiresAt=System.currentTimeMillis()+TvPairingCodec.LIFETIME_MS;
        uri=TvPairingCodec.pairingUri(localLanAddress(),socket.getLocalPort(),key);
    }

    static String localLanAddress() throws Exception {
        Enumeration<NetworkInterface> nets=NetworkInterface.getNetworkInterfaces();
        if (nets==null) throw new IllegalStateException("Wi-Fi에 연결해 주세요.");
        List<String> candidates=new ArrayList<>();
        while(nets.hasMoreElements()) {
            NetworkInterface network=nets.nextElement();
            if(!network.isUp() || network.isLoopback() || network.isVirtual())continue;
            Enumeration<java.net.InetAddress> addresses=network.getInetAddresses();
            while(addresses.hasMoreElements()) {
                java.net.InetAddress address=addresses.nextElement();
                if(address instanceof Inet4Address && address.isSiteLocalAddress())
                    candidates.add(address.getHostAddress());
            }
        }
        if(candidates.isEmpty())throw new IllegalStateException(
                "휴대폰과 TV를 같은 Wi-Fi에 연결해 주세요.");
        // Wi-Fi addresses on a TV are normally 192.168.x.x / 10.x.x.x.
        Collections.sort(candidates,(a,b)->Integer.compare(
                a.startsWith("192.168.")?0:a.startsWith("10.")?1:2,
                b.startsWith("192.168.")?0:b.startsWith("10.")?1:2));
        return candidates.get(0);
    }

    void start() {
        thread=new Thread(()->{
            int attempts=0;
            while(!stopped && System.currentTimeMillis()<expiresAt &&
                    attempts<20 && !accepted.get()) {
                try(Socket client=socket.accept()) {
                    attempts++;
                    client.setSoTimeout(5000);
                    handle(client);
                }catch(java.net.SocketTimeoutException timeout) {
                    // Continue until the 2-minute QR deadline.
                }catch(Exception invalid) {
                    // No account information or token is ever written to logs.
                }
            }
            close();
        },"filehub-tv-pairing");
        thread.setDaemon(true);
        thread.start();
    }

    private void handle(Socket client) throws Exception {
        InputStream in=client.getInputStream();
        String request=line(in,256);
        if(!"POST /pair HTTP/1.1".equals(request))return;
        int length=-1;
        for(int i=0;i<32;i++) {
            String header=line(in,1024);
            if(header.isEmpty())break;
            if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                length=Integer.parseInt(header.substring(15).trim());
        }
        if(length<29 || length>TvPairingCodec.MAX_PACKET ||
                System.currentTimeMillis()>expiresAt || accepted.get())return;
        byte[] packet=new byte[length];
        int offset=0;
        while(offset<length) {
            int count=in.read(packet,offset,length-offset);
            if(count<0)return;
            if(count>0)offset+=count;
        }
        byte[] decoded=TvPairingCodec.decrypt(packet,key);
        if (!accepted.compareAndSet(false,true))return;
        OutputStream out=client.getOutputStream();
        byte[] response="OK".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                "Cache-Control: no-store\r\nContent-Length: 2\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(response);out.flush();
        receiver.received(decoded);
    }

    private static String line(InputStream in,int max) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(int i=0;i<max;i++){
            int c=in.read();
            if(c<0)throw new java.io.EOFException();
            if(c=='\n')break;
            if(c!='\r')b.write(c);
        }
        if(b.size()>=max)throw new IllegalArgumentException("Header too large");
        return b.toString("US-ASCII");
    }

    static void send(TvPairingCodec.Destination destination, byte[] profiles) throws Exception {
        byte[] packet=TvPairingCodec.encrypt(profiles,destination.key);
        HttpURLConnection connection=(HttpURLConnection)new URL(
                "http://"+destination.host+":"+destination.port+"/pair").openConnection();
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5500);
        connection.setReadTimeout(6500);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/octet-stream");
        connection.setFixedLengthStreamingMode(packet.length);
        try {
            try(OutputStream out=connection.getOutputStream()) {out.write(packet);}
            if(connection.getResponseCode()!=200)
                throw new java.io.IOException("TV가 요청을 승인하지 않았습니다.");
        } finally {connection.disconnect();}
    }

    @Override public void close() {
        stopped=true;
        try{socket.close();}catch(Exception ignored){}
        java.util.Arrays.fill(key,(byte)0);
    }
}
