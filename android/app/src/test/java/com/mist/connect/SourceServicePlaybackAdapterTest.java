package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SourceServicePlaybackAdapterTest {
    SourceServicePlaybackAdapter owner(){return new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE);}
    static final class Fake implements SourceServicePlaybackAdapter.Session {
        final CountDownLatch entered=new CountDownLatch(1), finish=new CountDownLatch(1), abortEntered=new CountDownLatch(1);
        final boolean wait; volatile boolean source,sink,aborted; boolean stopFail,closeFail;
        Fake(boolean wait){this.wait=wait;}
        public boolean play() throws Exception {entered.countDown();if(wait)assertTrue(finish.await(2,TimeUnit.SECONDS));return true;}
        public void abort(){aborted=true;abortEntered.countDown();if(stopFail)throw new IllegalStateException("stop");}
        public void close(){if(closeFail)throw new IllegalStateException("close");source=true;sink=true;}
        public boolean sourceClosed(){return source;}public boolean sinkClosed(){return sink;}
    }
    void enqueue(SourceServicePlaybackAdapter o,long generation,long seq,String id,SourceServicePlaybackAdapter.Kind k,Fake f) {
        assertTrue(o.enqueue(generation,seq,id,k,()->f));
    }
    void join(SourceServicePlaybackAdapter o)throws Exception {assertTrue(o.awaitWorker(2500));}
    @Test public void normalFinalAndDuplicate()throws Exception {
        var o=owner();assertTrue(o.begin(1,3));Fake f=new Fake(false);
        enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,f);
        assertFalse(o.enqueue(1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,()->new Fake(false)));
        assertTrue(o.startNext());join(o);assertTrue(f.source && f.sink);
        AtomicInteger starts=new AtomicInteger();assertTrue(o.consumeListening(1,3,starts::incrementAndGet));
        assertFalse(o.consumeListening(1,3,starts::incrementAndGet));assertEquals(1,starts.get());
        assertFalse(o.begin(1,3));
    }
    @Test public void progressNeverListensFinalEvictsAndWaitsForOldCleanup()throws Exception {
        var o=owner();o.begin(1,0);Fake p=new Fake(true),f=new Fake(false);
        enqueue(o,1,0,"p",SourceServicePlaybackAdapter.Kind.PROGRESS,p);o.startNext();assertTrue(p.entered.await(1,TimeUnit.SECONDS));
        enqueue(o,1,1,"discard",SourceServicePlaybackAdapter.Kind.PROGRESS,new Fake(false));
        enqueue(o,1,2,"f",SourceServicePlaybackAdapter.Kind.FINAL,f);
        assertTrue(p.abortEntered.await(1,TimeUnit.SECONDS));assertTrue(p.aborted);assertFalse(o.startNext());assertFalse(o.consumeListening(1,0,()->fail()));
        p.finish.countDown();join(o);assertTrue(p.source && p.sink);assertTrue(o.startNext());join(o);
        assertTrue(o.consumeListening(1,0,()->{}));assertFalse(o.startNext());
    }
    @Test public void offDoesNotAcknowledgeUntilWorkerJoinedAndBothClosed()throws Exception {
        var o=owner();o.begin(1,0);Fake p=new Fake(true);enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,p);
        o.startNext();assertTrue(p.entered.await(1,TimeUnit.SECONDS));
        assertFalse(o.stopAndJoin(1));assertTrue(p.abortEntered.await(1,TimeUnit.SECONDS));assertTrue(p.aborted);assertFalse(p.source || p.sink);
        p.finish.countDown();assertTrue(o.stopAndJoin(2500));assertTrue(p.source && p.sink);
        assertFalse(o.consumeListening(1,0,()->fail()));
    }
    @Test public void destroyPermanentAndStaleCompletionCannotResurrect()throws Exception {
        var o=owner();o.begin(1,0);Fake f=new Fake(true);enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,f);
        o.startNext();assertTrue(f.entered.await(1,TimeUnit.SECONDS));o.destroy();f.finish.countDown();join(o);
        assertFalse(o.begin(2,1));assertFalse(o.consumeListening(1,0,()->fail()));assertTrue(o.stopAndJoin(100));
    }
    @Test public void reconnectFreshGenerationWaitsOldWorker()throws Exception {
        var o=owner();o.begin(1,0);Fake old=new Fake(true),fresh=new Fake(false);
        enqueue(o,1,0,"old",SourceServicePlaybackAdapter.Kind.FINAL,old);o.startNext();assertTrue(old.entered.await(1,TimeUnit.SECONDS));
        assertTrue(o.begin(2,1));enqueue(o,2,0,"new",SourceServicePlaybackAdapter.Kind.FINAL,fresh);
        assertFalse(o.startNext());old.finish.countDown();join(o);assertFalse(o.consumeListening(1,0,()->fail()));
        assertTrue(o.startNext());join(o);assertTrue(o.consumeListening(2,1,()->{}));
    }
    @Test public void stopFailurePoisonsEvenWhenCloseSucceeds()throws Exception {
        var o=owner();o.begin(1,0);Fake f=new Fake(true);f.stopFail=true;
        enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,f);o.startNext();assertTrue(f.entered.await(1,TimeUnit.SECONDS));
        o.off();f.finish.countDown();join(o);assertTrue(o.blocked());assertFalse(o.stopAndJoin(100));assertFalse(o.begin(2,0));
    }
    @Test public void closeFailurePoisons()throws Exception {
        var o=owner();o.begin(1,0);Fake f=new Fake(false);f.closeFail=true;
        enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,f);o.startNext();join(o);
        assertTrue(o.blocked());assertFalse(o.stopAndJoin(100));
    }
    @Test public void offAndConsentInvalidateCompletionAtEffectBoundary()throws Exception {
        var o=owner();o.begin(1,0);enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,new Fake(false));o.startNext();join(o);
        o.consentChanged(1);assertFalse(o.consumeListening(1,0,()->fail()));assertFalse(o.consumeListening(1,1,()->fail()));
    }
    @Test public void offSerializesWithActualListeningEffect()throws Exception {
        var o=owner();o.begin(1,0);enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,new Fake(false));o.startNext();join(o);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),offCalled=new CountDownLatch(1);
        AtomicBoolean result=new AtomicBoolean();
        Thread t=new Thread(()->result.set(o.consumeListening(1,0,()->{entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}})));
        t.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        Thread off=new Thread(()->{offCalled.countDown();o.off();});off.start();assertTrue(offCalled.await(1,TimeUnit.SECONDS));
        release.countDown();t.join(2000);off.join(2000);assertFalse(t.isAlive() || off.isAlive());assertTrue(result.get());
        assertFalse(o.consumeListening(1,0,()->fail()));
    }
    @Test public void liveServiceGateIsNotAnOptInPreference() {
        try{new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.LIVE_SERVICE);fail();}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("lease"));}
    }
    static byte[] hps()throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0x48505331);d.writeShort(1);d.writeBytes("r");d.writeShort(1);d.writeBytes("a");d.writeLong(2);
        d.writeByte(1);d.writeInt(4);d.writeInt(0);d.writeByte(2);d.writeInt(8);d.writeLong(2);return b.toByteArray();
    }
    static class Track implements StreamingAudioTrackSink.Track {
        int frames,heads,plays,pauses,flushes,releases;boolean failRelease,failPause;
        public void play(){plays++;}public int write(byte[] b,int o,int n){frames+=n/2;return n;}
        public int head(){heads++;return frames;}public void pause(){pauses++;if(failPause)throw new IllegalStateException();}
        public void flush(){flushes++;}public void release(){releases++;if(failRelease)throw new IllegalStateException();}
    }
    static StreamingPcmPlayer.Clock clock(){return new StreamingPcmPlayer.Clock(){public long millis(){return System.nanoTime()/1000000;}public void pause()throws InterruptedException{Thread.sleep(1);}};}
    @Test public void realBufferedAndPcmSessionUseSameOwnerAndCloseBeforeNextSink()throws Exception {
        var o=owner();o.begin(1,0);byte[] bytes={1,2,3};AtomicBoolean legacyClosed=new AtomicBoolean();
        SourceBufferedPlaybackSession.Backend backend=new SourceBufferedPlaybackSession.Backend(){
            Runnable done;public void prepare(byte[] b,Runnable c,Runnable f){done=c;}public void start(){done.run();}
            public void abort(){}public void close(){legacyClosed.set(true);}
        };
        o.enqueue(1,0,"legacy",SourceServicePlaybackAdapter.Kind.FINAL,()->new SourceBufferedPlaybackSession(bytes,backend));
        o.startNext();join(o);assertArrayEquals(new byte[3],bytes);assertTrue(legacyClosed.get());
        assertTrue(o.stopAndJoin(100));assertTrue(o.begin(2,0));Track track=new Track();AtomicInteger rawCloses=new AtomicInteger();
        o.enqueue(2,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,()->{
            assertTrue(legacyClosed.get());return new SourcePcmPlaybackSession("r","a",2,
                ()->new ByteArrayInputStream(hps()){public void close(){rawCloses.incrementAndGet();}},new StreamingAudioTrackSink(track),clock());});
        assertTrue(o.startNext());join(o);assertEquals(1,track.plays);assertEquals(2,track.frames);assertEquals(1,track.releases);
        assertEquals(1,rawCloses.get());assertTrue(o.consumeListening(2,0,()->{}));
    }
    @Test public void pcmFirstCloseFailureCannotBeErasedByLaterCleanup()throws Exception {
        var o=owner();o.begin(2,0);Track track=new Track();AtomicInteger closes=new AtomicInteger();
        o.enqueue(2,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,()->new SourcePcmPlaybackSession("r","a",2,
            ()->new ByteArrayInputStream(hps()){public void close()throws IOException{if(closes.incrementAndGet()==1)throw new IOException();}},
            new StreamingAudioTrackSink(track),clock()));o.startNext();join(o);
        assertEquals(1,closes.get());assertEquals(1,track.releases);assertTrue(o.blocked());assertFalse(o.stopAndJoin(100));
    }
    @Test public void actualTrackFacadeNonblockingWriteHeadWrapAbortAndRelease() {
        Track track=new Track();StreamingAudioTrackSink sink=new StreamingAudioTrackSink(track);
        sink.start();assertEquals(4,sink.write(new byte[4],0,4));track.frames=-1;
        assertEquals(4294967295L,sink.playedFrames());track.frames=1;assertEquals(4294967297L,sink.playedFrames());
        sink.abort();sink.abort();assertEquals(1,track.pauses);assertEquals(1,track.flushes);assertEquals(-1,sink.write(new byte[2],0,2));
        sink.close();sink.close();assertEquals(1,track.releases);
    }
    @Test public void stopAckEffectIsWithheldUntilCleanup()throws Exception {
        var o=owner();o.begin(1,0);Fake f=new Fake(true);
        enqueue(o,1,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,f);o.startNext();assertTrue(f.entered.await(1,TimeUnit.SECONDS));
        AtomicInteger sent=new AtomicInteger();
        try{o.stopAndAcknowledge(1,()->{sent.incrementAndGet();return new byte[0];});fail();}
        catch(IOException expected){assertEquals("device_cleanup_unconfirmed",expected.getMessage());}
        assertEquals(0,sent.get());f.finish.countDown();join(o);
        o.stopAndAcknowledge(100,()->{assertTrue(f.source && f.sink);sent.incrementAndGet();return new byte[0];});
        assertEquals(1,sent.get());
    }
    @Test public void actualAuthenticatedLoopbackTransportCarriesStreamWithoutRedirectOrRetry()throws Exception {
        var o=owner();String token="fixture_"+"x".repeat(32);
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread host=new Thread(()->{try {
                for(String path:new String[]{"/stop-ack","/stream"})try(java.net.Socket s=server.accept()) {
                    s.setSoTimeout(2000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                    assertEquals("POST "+path+" HTTP/1.1",in.readLine());String line;int size=-1;boolean auth=false;
                    while(!(line=in.readLine()).isEmpty()) {
                        if(line.startsWith("Authorization: "))auth=line.equals("Authorization: Bearer "+token);
                        if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                    }
                    assertTrue(auth);assertEquals(2,size);char[] body=new char[2];assertEquals(2,in.read(body));assertEquals("{}",new String(body));
                    byte[] result=path.equals("/stream")?hps():"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+result.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.write(result);out.flush();
                }
            }catch(Throwable t){failure.set(t);}});host.start();
            SourceLoopbackPlaybackTransport transport=new SourceLoopbackPlaybackTransport(server.getLocalPort(),token,o);
            assertArrayEquals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),transport.stopAck("{}",100));
            o.begin(2,0);Track track=new Track();
            o.enqueue(2,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,()->new SourcePcmPlaybackSession("r","a",2,
                ()->transport.stream("{}"),new StreamingAudioTrackSink(track),clock()));o.startNext();join(o);
            host.join(3000);assertFalse(host.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
            assertTrue(o.consumeListening(2,0,()->{}));assertEquals(2,track.frames);
        }
    }
    @Test public void actualTrackReleaseFailurePoisonsAdapter()throws Exception {
        var o=owner();o.begin(2,0);Track t=new Track();t.failRelease=true;
        o.enqueue(2,0,"a",SourceServicePlaybackAdapter.Kind.FINAL,()->new SourcePcmPlaybackSession("r","a",2,
            ()->new ByteArrayInputStream(hps()),new StreamingAudioTrackSink(t),clock()));o.startNext();join(o);
        assertTrue(o.blocked());assertEquals(1,t.releases);assertFalse(o.stopAndJoin(100));
    }
}
