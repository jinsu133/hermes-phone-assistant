package com.mist.connect;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Fixed loopback status transport with one absolute connect/header/body deadline. */
final class PairingHttp implements AutoCloseable {
    private final Socket socket = new Socket();
    private final LongSupplier clock;
    private volatile boolean cancelled;
    PairingHttp(LongSupplier clock) { this.clock=clock; }

    byte[] status(String token, BooleanSupplier live) throws IOException {
        return fetch(5001, token, 4000, live);
    }
    byte[] post(String path, String token, byte[] body, BooleanSupplier live) throws IOException {
        if (!(path.equals("/v1/pairing/endpoint") || path.equals("/v1/pairing/result")) || body.length>4096)
            throw new IOException("pairing_request_invalid");
        return request(5001, token, 10000, live, path, body);
    }
    // Package-private port/deadline injection for loopback-only offline tests.
    byte[] fetch(int port, String token, long budget, BooleanSupplier live) throws IOException {
        if (budget>4000) throw new IOException("pairing_request_invalid");
        return request(port, token, budget, live, "/status", null);
    }
    private byte[] request(int port, String token, long budget, BooleanSupplier live, String path, byte[] data) throws IOException {
        if (token==null || !token.matches("[A-Za-z0-9_-]{32,512}") || budget<1 || budget>10000)
            throw new IOException("pairing_request_invalid");
        final long deadline=clock.getAsLong()+budget;
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),port), remaining(deadline,live));
            String request=(data==null ? "GET " : "POST ")+path+" HTTP/1.1\r\nHost: 127.0.0.1:5001\r\nAuthorization: Bearer "+token+"\r\nConnection: close\r\n"+
                (data==null ? "" : "Content-Type: application/json\r\nContent-Length: "+data.length+"\r\n")+"\r\n";
            remaining(deadline,live);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            if (data!=null) socket.getOutputStream().write(data);
            InputStream in=socket.getInputStream();
            ByteArrayOutputStream header=new ByteArrayOutputStream();
            int matched=0;
            byte[] end={'\r','\n','\r','\n'};
            while (matched<4) {
                socket.setSoTimeout(remaining(deadline,live));
                int b=in.read();
                if (b<0 || header.size()>=8192) throw new IOException("pairing_header_invalid");
                header.write(b);
                matched=b==end[matched] ? matched+1 : (b=='\r' ? 1 : 0);
            }
            String[] lines=new String(header.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");
            if (!lines[0].matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("pairing_voice_auth_failed");
            Map<String,String> fields=new HashMap<>();
            for (int i=1;i<lines.length;i++) {
                int colon=lines[i].indexOf(':');
                if (colon<1) throw new IOException("pairing_header_invalid");
                String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9-]+") || fields.put(name,lines[i].substring(colon+1).trim())!=null)
                    throw new IOException("pairing_header_invalid");
            }
            String size=fields.get("content-length");
            if (fields.containsKey("transfer-encoding") || fields.containsKey("content-encoding") || size==null || !size.matches("[0-9]{1,5}"))
                throw new IOException("pairing_response_invalid");
            int length=Integer.parseInt(size);
            if (length<1 || length>32768) throw new IOException("pairing_response_invalid");
            byte[] body=new byte[length];
            try {
                int offset=0;
                while (offset<length) {
                    socket.setSoTimeout(remaining(deadline,live));
                    int n=in.read(body,offset,length-offset);
                    if (n<=0) throw new IOException("pairing_response_truncated");
                    offset+=n;
                }
                remaining(deadline,live);
                return body;
            } catch (IOException | RuntimeException e) { Arrays.fill(body,(byte)0); throw e; }
        } finally { close(); }
    }
    private int remaining(long deadline, BooleanSupplier live) throws IOException {
        long left=deadline-clock.getAsLong();
        if (cancelled || !live.getAsBoolean()) throw new IOException("pairing_stopped");
        if (left<=0) throw new SocketTimeoutException("pairing_deadline");
        return (int)Math.min(1500,left);
    }
    @Override public void close() {
        cancelled=true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
