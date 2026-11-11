package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class StreamingPlaybackOwnerTest {
    static void await(CountDownLatch latch) {
        try { assertTrue("barrier timeout", latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    static class Fake implements StreamingPcmPlayer.Sink, StreamingPcmPlayer.Clock {
        long frames; boolean closed, aborted; int starts; boolean failAbort, failClose;
        CountDownLatch atClose, releaseClose, atHead, releaseHead;
        public void start() { starts++; }
        public int write(byte[] b, int o, int n) { frames+=n/2; return n; }
        public long playedFrames() {
            if (atHead!=null) {atHead.countDown();await(releaseHead);} return frames;
        }
        public void abort() {if(failAbort)throw new IllegalStateException("stop failed");aborted=true;}
        public void close() {
            if(atClose!=null){atClose.countDown();await(releaseClose);}
            if(failClose)throw new IllegalStateException("close failed"); closed=true;
        }
        public long millis() { return 0; }
        public void pause() {}
    }
    static byte[] wire(String request,String audio,long generation) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0x48505331);d.writeUTF(request);d.writeUTF(audio);d.writeLong(generation);
        d.writeByte(1);d.writeInt(8);d.write(new byte[8]);d.writeByte(2);d.writeInt(8);d.writeLong(4);
        return b.toByteArray();
    }
    static StreamingPlaybackOwner.Source source(String a,long g) {
        return () -> new ByteArrayInputStream(wire("r",a,g));
    }
    static void submit(StreamingPlaybackOwner o,String a,long seq,StreamingPlaybackOwner.Kind k) {
        assertTrue(o.enqueue("r",1,a,seq,k,source(a,1)));
    }
    @Test public void duplicateConcurrentBeginOnlyOneWins() throws Exception {
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->f,f);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Boolean> call=()->{await(start);return o.begin("r",1);};
            Future<Boolean> a=pool.submit(call),b=pool.submit(call);start.countDown();
            assertNotEquals(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS));
            assertFalse(o.begin("old",0));
        } finally {pool.shutdownNow();}
    }
    @Test public void boundedFifoDuplicatesAndTerminalPriority() throws Exception {
        List<Fake> sinks=new ArrayList<>();Fake clock=new Fake();
        StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->{Fake f=new Fake();sinks.add(f);return f;},clock);
        assertTrue(o.begin("r",1));submit(o,"p1",1,StreamingPlaybackOwner.Kind.PROGRESS);
        submit(o,"p2",2,StreamingPlaybackOwner.Kind.PROGRESS);
        assertFalse(o.enqueue("r",1,"overflow",3,StreamingPlaybackOwner.Kind.PROGRESS,source("overflow",1)));
        assertEquals("p1",o.runNext().audioId);assertNull(o.takeEligibility());
        assertEquals("p2",o.runNext().audioId);
        assertFalse(o.enqueue("r",1,"repeat",2,StreamingPlaybackOwner.Kind.PROGRESS,source("repeat",1)));
        submit(o,"p3",3,StreamingPlaybackOwner.Kind.PROGRESS);
        submit(o,"final",4,StreamingPlaybackOwner.Kind.FINAL);
        assertEquals(1,o.queued());assertFalse(o.enqueue("r",1,"late",5,StreamingPlaybackOwner.Kind.PROGRESS,source("late",1)));
        assertEquals("final",o.runNext().audioId);assertNull(o.runNext());
        assertEquals("final",o.takeEligibility().receipt.audioId);assertNull(o.takeEligibility());
        assertEquals(3,sinks.size());for(Fake f:sinks)assertTrue(f.closed);
    }
    @Test public void cancelWinsBeforeFinalReceipt() throws Exception {
        // Source barrier is outside owner lock: cancel invalidates while worker owns sink.
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->f,f);o.begin("r",1);
        CountDownLatch opened=new CountDownLatch(1),resume=new CountDownLatch(1);
        assertTrue(o.enqueue("r",1,"a",1,StreamingPlaybackOwner.Kind.FINAL,()->{
            opened.countDown();await(resume);return new ByteArrayInputStream(wire("r","a",1));}));
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<StreamingPcmPlayer.Receipt> result=pool.submit(o::runNext);await(opened);
            o.cancel();resume.countDown();assertNull(result.get(3,TimeUnit.SECONDS));
            assertNull(o.takeEligibility());assertEquals(0,f.starts);assertTrue(f.closed);
        } finally {resume.countDown();pool.shutdownNow();}
    }
    @Test public void completedSnapshotNeverSurvivesOffAsPermission() throws Exception {
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(1,()->f,f);o.begin("r",1);
        submit(o,"a",1,StreamingPlaybackOwner.Kind.APPROVAL_NOTICE);
        StreamingPcmPlayer.Receipt historical=o.runNext();assertNotNull(historical);
        o.off();assertNull(o.takeEligibility());assertFalse(o.isAccepted(historical));
        assertFalse(o.begin("r",1));
    }
    @Test public void oldGenerationCallbackCannotCompleteNewRequest() throws Exception {
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(1,()->f,f);o.begin("r",1);
        submit(o,"a",1,StreamingPlaybackOwner.Kind.FINAL);StreamingPcmPlayer.Receipt old=o.runNext();
        assertTrue(o.begin("r",2));assertFalse(o.isAccepted(old));assertNull(o.takeEligibility());
        assertFalse(o.enqueue("r",1,"stale",2,StreamingPlaybackOwner.Kind.FINAL,source("stale",1)));
    }
    @Test public void finalDuringProgressWaitsForWorkerCleanup() throws Exception {
        List<Fake> sinks=new ArrayList<>();Fake clock=new Fake();
        StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->{Fake f=new Fake();sinks.add(f);return f;},clock);o.begin("r",1);
        CountDownLatch opened=new CountDownLatch(1),resume=new CountDownLatch(1);
        o.enqueue("r",1,"p",1,StreamingPlaybackOwner.Kind.PROGRESS,()->new ByteArrayInputStream(wire("r","p",1)) {
            public synchronized int read() {
                if(available()==0){opened.countDown();await(resume);}return super.read();
            }
        });
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<StreamingPcmPlayer.Receipt> old=pool.submit(o::runNext);await(opened);
            assertEquals(1,sinks.get(0).starts);assertEquals(4,sinks.get(0).frames);
            submit(o,"queued",2,StreamingPlaybackOwner.Kind.PROGRESS);submit(o,"f",3,StreamingPlaybackOwner.Kind.FINAL);
            assertTrue(sinks.get(0).aborted);
            assertNull(o.runNext());assertEquals(1,sinks.size());assertEquals(1,o.queued());
            resume.countDown();assertNull(old.get(3,TimeUnit.SECONDS));assertTrue(sinks.get(0).closed);
            assertEquals("f",o.runNext().audioId);assertEquals(2,sinks.size());
        } finally {resume.countDown();pool.shutdownNow();}
    }
    @Test public void stopFailurePermanentlyBlocksReplacement() throws Exception {
        Fake f=new Fake();f.failAbort=true;
        StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->f,f);o.begin("r",1);
        CountDownLatch opened=new CountDownLatch(1),resume=new CountDownLatch(1);
        o.enqueue("r",1,"p",1,StreamingPlaybackOwner.Kind.PROGRESS,()->{opened.countDown();await(resume);return new ByteArrayInputStream(wire("r","p",1));});
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> run=pool.submit(o::runNext);await(opened);
            assertFalse(o.enqueue("r",1,"f",2,StreamingPlaybackOwner.Kind.FINAL,source("f",1)));
            assertTrue(o.blocked());resume.countDown();run.get(3,TimeUnit.SECONDS);
            assertNull(o.runNext());assertFalse(o.begin("new",2));assertNull(o.takeEligibility());
        } finally {resume.countDown();pool.shutdownNow();}
    }
    @Test public void failedCloseCannotIssueReceipt() throws Exception {
        Fake f=new Fake();f.failClose=true;StreamingPlaybackOwner o=new StreamingPlaybackOwner(1,()->f,f);o.begin("r",1);
        submit(o,"a",1,StreamingPlaybackOwner.Kind.FINAL);assertNull(o.runNext());assertTrue(o.blocked());assertNull(o.takeEligibility());
    }
    @Test public void cancelBetweenPlayerReceiptAndOwnerAcceptance() throws Exception {
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(1,()->f,f);o.begin("r",1);
        CountDownLatch closing=new CountDownLatch(1),release=new CountDownLatch(1);
        o.enqueue("r",1,"a",1,StreamingPlaybackOwner.Kind.FINAL,()->new ByteArrayInputStream(wire("r","a",1)) {
            public void close() {closing.countDown();await(release);}
        });
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<StreamingPcmPlayer.Receipt> run=pool.submit(o::runNext);await(closing);
            // Player has checked the final playback head and constructed a receipt,
            // but it is not accepted yet. Returning a snapshot would be unsafe.
            assertEquals(4,f.frames);o.cancel();release.countDown();
            assertNull(run.get(3,TimeUnit.SECONDS));assertNull(o.takeEligibility());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void liveProgressStopsBeforeFinalSinkAndOldEpochCannotWin() throws Exception {
        List<Fake> sinks=new ArrayList<>();Fake clock=new Fake();
        StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->{Fake f=new Fake();sinks.add(f);return f;},clock);
        o.begin("r",1);CountDownLatch eof=new CountDownLatch(1),release=new CountDownLatch(1);
        o.enqueue("r",1,"p",1,StreamingPlaybackOwner.Kind.PROGRESS,()->new ByteArrayInputStream(wire("r","p",1)) {
            public synchronized int read() {
                if(available()==0){eof.countDown();await(release);}return super.read();
            }
        });
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<StreamingPcmPlayer.Receipt> run=pool.submit(o::runNext);await(eof);
            assertEquals(1,sinks.get(0).starts);assertEquals(4,sinks.get(0).frames);
            assertTrue(o.begin("r",2));
            assertFalse(o.enqueue("r",1,"staleFinal",2,StreamingPlaybackOwner.Kind.FINAL,source("staleFinal",1)));
            assertTrue(o.enqueue("r",2,"f",1,StreamingPlaybackOwner.Kind.FINAL,source("f",2)));
            assertNull(o.runNext());assertEquals(1,sinks.size());
            release.countDown();assertNull(run.get(3,TimeUnit.SECONDS));assertTrue(sinks.get(0).closed);
            assertEquals(2,o.runNext().generation);assertEquals(2,sinks.size());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void duplicateConcurrentEnqueueAndPumpNeverAllocateTwice() throws Exception {
        Fake f=new Fake();int[] allocated={0};StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->{allocated[0]++;return f;},f);
        o.begin("r",1);CountDownLatch start=new CountDownLatch(1),opened=new CountDownLatch(1),release=new CountDownLatch(1);
        StreamingPlaybackOwner.Source source=()->{opened.countDown();await(release);return new ByteArrayInputStream(wire("r","a",1));};
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> submit=()->{await(start);return o.enqueue("r",1,"a",1,StreamingPlaybackOwner.Kind.FINAL,source);};
            Future<Boolean> a=pool.submit(submit),b=pool.submit(submit);start.countDown();
            assertNotEquals(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS));
            Future<StreamingPcmPlayer.Receipt> playing=pool.submit(o::runNext);await(opened);
            assertNull(pool.submit(o::runNext).get(3,TimeUnit.SECONDS));assertEquals(1,allocated[0]);
            release.countDown();assertNotNull(playing.get(3,TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void reusedAudioIdRejectedEvenWithNewSequence() throws Exception {
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(2,()->f,f);o.begin("r",1);
        submit(o,"a",1,StreamingPlaybackOwner.Kind.PROGRESS);
        assertFalse(o.enqueue("r",1,"a",2,StreamingPlaybackOwner.Kind.FINAL,source("a",1)));
        assertEquals(1,o.queued());
    }
    @Test public void malformedStreamAndOpenFailureDoNotGrantListening() throws Exception {
        Fake f=new Fake();StreamingPlaybackOwner o=new StreamingPlaybackOwner(1,()->f,f);o.begin("r",1);
        o.enqueue("r",1,"a",1,StreamingPlaybackOwner.Kind.FINAL,()->new ByteArrayInputStream(new byte[0]));
        assertNull(o.runNext());assertTrue(f.closed);assertNull(o.takeEligibility());
        Fake g=new Fake();StreamingPlaybackOwner p=new StreamingPlaybackOwner(1,()->g,g);p.begin("r",1);
        p.enqueue("r",1,"a",1,StreamingPlaybackOwner.Kind.FINAL,()->{throw new IOException("open");});
        assertNull(p.runNext());assertTrue(g.closed);assertNull(p.takeEligibility());
    }
}
