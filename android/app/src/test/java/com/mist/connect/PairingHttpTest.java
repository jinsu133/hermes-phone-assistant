package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback sockets and production transport; no Android/device/provider. */
public class PairingHttpTest {
    private static final String KEY="VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV";
    interface Send { void run(Socket peer) throws Exception; }
    static class Peer implements AutoCloseable {
        final ServerSocket server;
        final AtomicReference<Socket> accepted=new AtomicReference<>();
        final CountDownLatch request=new CountDownLatch(1);
        final Thread thread;
        Peer(Send send) throws Exception {
            server=new ServerSocket(0,1,InetAddress.getByAddress(new byte[]{127,0,0,1}));
            thread=new Thread(()->{
                try(Socket socket=server.accept()) {
                    accepted.set(socket);socket.setSoTimeout(1000);
                    BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line;while((line=in.readLine())!=null && !line.isEmpty()) {}
                    request.countDown();send.run(socket);
                } catch(Exception ignored) {}
            });thread.start();
        }
        int port(){return server.getLocalPort();}
        public void close() throws Exception {
            server.close();Socket s=accepted.get();if(s!=null)s.close();thread.interrupt();thread.join(1500);
            assertFalse("peer cleanup",thread.isAlive());
        }
    }
    static PairingHttp client(){return new PairingHttp(()->System.nanoTime()/1000000);}
    static void write(Socket s,String text) throws Exception {s.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();}
    static void good(Socket s) throws Exception {write(s,"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");}
    @Test public void normalAndNextRequestAfterFailure() throws Exception {
        try(Peer p=new Peer(s->write(s,"HTTP/1.1 401 Denied\r\nContent-Length: 2\r\n\r\n{}"))) {
            try{client().fetch(p.port(),KEY,1000,()->true);fail();}catch(IOException expected){}
        }
        try(Peer p=new Peer(PairingHttpTest::good)) {
            assertEquals("{}",new String(client().fetch(p.port(),KEY,1000,()->true),StandardCharsets.UTF_8));
        }
    }
    @Test public void headerDelayHitsTotalDeadline() throws Exception {
        long start=System.nanoTime();
        try(Peer p=new Peer(s->{Thread.sleep(1000);good(s);})) {
            try{client().fetch(p.port(),KEY,150,()->true);fail();}catch(SocketTimeoutException expected){}
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1200);
    }
    @Test public void dripBodyDoesNotResetDeadline() throws Exception {
        long start=System.nanoTime();
        try(Peer p=new Peer(s->{write(s,"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n");for(int i=0;i<100;i++){write(s,"x");Thread.sleep(40);}})) {
            try{client().fetch(p.port(),KEY,220,()->true);fail();}catch(SocketTimeoutException expected){}
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1400);
    }
    @Test public void closeUnblocksOutstandingHeaderRead() throws Exception {
        try(Peer p=new Peer(s->{Thread.sleep(2000);good(s);})) {
            PairingHttp c=client();ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> done=worker.submit(()->{try{c.fetch(p.port(),KEY,4000,()->true);return false;}catch(IOException expected){return true;}});
                assertTrue(p.request.await(1,TimeUnit.SECONDS));c.close();assertTrue(done.get(1,TimeUnit.SECONDS));
            } finally {c.close();worker.shutdownNow();assertTrue(worker.awaitTermination(1,TimeUnit.SECONDS));}
        }
    }
    @Test public void duplicateAndOversizedFramingRejected() throws Exception {
        for(String headers:new String[]{"Content-Length: 2\r\nContent-Length: 2", "Content-Length: 32769", "Transfer-Encoding: chunked\r\nContent-Length: 2"}) {
            try(Peer p=new Peer(s->write(s,"HTTP/1.1 200 OK\r\n"+headers+"\r\n\r\n{}"))) {
                try{client().fetch(p.port(),KEY,1000,()->true);fail();}catch(IOException expected){}
            }
        }
    }
}
