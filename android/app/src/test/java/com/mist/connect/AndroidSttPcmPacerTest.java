package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Java pacer/completion, fake clock and sink. Not Android pipe/recognizer evidence. */
public class AndroidSttPcmPacerTest {
    static final class Clock implements AndroidSttPcmPacer.Clock {
        long nanos, slept, maxSleep; int waits; Runnable onSleep;
        public long now() { return nanos; }
        public void sleep(long n) { assertTrue(n > 0); maxSleep=Math.max(maxSleep,n);
            nanos+=n; slept+=n; waits++; if(onSleep!=null)onSleep.run(); }
    }
    private byte[] supply(int size, AndroidSttPcmPacer.Mode mode, long writeCost) throws Exception {
        Clock clock=new Clock(); AndroidSttPcmPacer pacer=new AndroidSttPcmPacer(mode,clock);
        byte[] input=new byte[size]; for(int i=0;i<size;i++)input[i]=(byte)(i*31);
        ByteArrayOutputStream sink=new ByteArrayOutputStream();
        AndroidSttCompletion c=new AndroidSttCompletion(size);
        int at=0;
        while(at<size) {
            int n=Math.min(pacer.chunkBytes(),size-at);
            assertTrue(pacer.beforeWrite(at,n,()->true));
            if(mode==AndroidSttPcmPacer.Mode.REALTIME_20MS)
                assertTrue(clock.nanos >= ((long)at+n)*1_000_000_000L/32000);
            sink.write(input,at,n); at+=n; c.supplied(n); clock.nanos+=writeCost;
        }
        sink.close(); c.normalClose(); c.cleanup(true);
        c.offer("fixture",null,AndroidSttTrace.Terminal.SEGMENT_END,0,null);
        assertNull(c.seal().error); assertArrayEquals(input,sink.toByteArray());
        assertTrue(clock.maxSleep<=20_000_000L);
        if(mode==AndroidSttPcmPacer.Mode.FAST)assertEquals(0,clock.waits);
        if(mode==AndroidSttPcmPacer.Mode.REALTIME_20MS && writeCost==0)
            assertEquals((long)size*1_000_000_000L/32000,clock.nanos);
        return sink.toByteArray();
    }
    @Test public void partialFinalBlockIsLosslessAndEofAtSampleDeadline() throws Exception {
        supply(642,AndroidSttPcmPacer.Mode.REALTIME_20MS,0);
    }
    @Test public void maximumPcmHasExactByteAccounting() throws Exception {
        supply(960000,AndroidSttPcmPacer.Mode.REALTIME_20MS,0);
    }
    @Test public void fastAndPacedHaveIdenticalBytesAndMatchedChunks() throws Exception {
        assertArrayEquals(supply(32002,AndroidSttPcmPacer.Mode.FAST,0),
            supply(32002,AndroidSttPcmPacer.Mode.REALTIME_20MS,0));
    }
    @Test public void backpressureNeverDropsCatchupBytes() throws Exception {
        supply(32002,AndroidSttPcmPacer.Mode.REALTIME_20MS,35_000_000L);
    }
    @Test public void writeTimeIsNotAddedToEveryDeadline() throws Exception {
        Clock clock=new Clock(); AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,clock);
        assertTrue(p.beforeWrite(0,640,()->true)); clock.nanos+=7_000_000L;
        assertTrue(p.beforeWrite(640,640,()->true)); assertEquals(40_000_000L,clock.nanos);
        assertEquals(33_000_000L,clock.slept);
    }
    @Test public void cancelBeforeWaitDoesNotWrite() throws Exception {
        Clock c=new Clock(); AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,c);
        assertFalse(p.beforeWrite(0,640,()->false)); assertEquals(0,c.waits);
    }
    @Test public void cancelAtDeadlineIsRecheckedBeforeWrite() throws Exception {
        Clock c=new Clock(); AtomicBoolean active=new AtomicBoolean(true); c.onSleep=()->active.set(false);
        AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,c);
        assertFalse(p.beforeWrite(0,640,active::get)); assertEquals(1,c.waits);
    }
    @Test public void terminalDuringWaitDoesNotStartAnotherWrite() throws Exception {
        Clock clock=new Clock(); AndroidSttCompletion c=new AndroidSttCompletion(1280);
        clock.onSleep=()->c.offer("fixture",null,AndroidSttTrace.Terminal.RESULT,0,null);
        AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,clock);
        assertFalse(p.beforeWrite(0,640,()->!c.isTerminal())); c.normalClose(); c.cleanup(true);
        AndroidSttCompletion.Outcome out=c.seal(); assertEquals("stt_input_incomplete",out.error); assertEquals("",out.text);
    }
    @Test public void cancellationJoinsWaitingWorkerAndCannotSealSuccess() throws Exception {
        AndroidSttRequest request=new AndroidSttRequest(1280); AndroidSttCompletion c=request.claim(1280);
        CountDownLatch sleeping=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>(); AtomicBoolean wrote=new AtomicBoolean();
        AndroidSttPcmPacer.Clock clock=new AndroidSttPcmPacer.Clock() {
            public long now(){return 0;}
            public void sleep(long n) throws InterruptedException {
                sleeping.countDown(); if(!release.await(500,TimeUnit.MILLISECONDS))throw new AssertionError("barrier_timeout");
            }
        };
        Thread worker=new Thread(()->{try {
            AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,clock);
            wrote.set(p.beforeWrite(0,640,request::isActive));
        }catch(Throwable e){failure.set(e);}},"r04-test-writer");
        worker.start();
        try { assertTrue(sleeping.await(500,TimeUnit.MILLISECONDS)); request.cancel(); }
        finally { release.countDown(); worker.join(500); if(worker.isAlive()){worker.interrupt();worker.join(500);} }
        assertFalse(worker.isAlive()); assertNull(failure.get()); assertFalse(wrote.get());
        c.normalClose();c.cleanup(true);c.offer("fixture",null,AndroidSttTrace.Terminal.RESULT,0,null);
        AndroidSttCompletion.Outcome out=c.seal();assertEquals("stt_cancelled",out.error);assertEquals("",out.text);
    }
    @Test public void interruptDoesNotGrantAWrite() throws Exception {
        Thread.currentThread().interrupt();
        try {
            AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,new Clock());
            assertThrows(InterruptedException.class,()->p.beforeWrite(0,640,()->true));
        } finally { Thread.interrupted(); }
    }
    @Test public void failedWriteCannotCountAsSuppliedOrEof() {
        AndroidSttCompletion c=new AndroidSttCompletion(1280);
        c.supplied(640);c.writerFailed();c.cleanup(true);c.offer("fixture",null,AndroidSttTrace.Terminal.RESULT,0,null);
        AndroidSttCompletion.Outcome out=c.seal();assertEquals("stt_input_incomplete",out.error);assertEquals("",out.text);
    }
    @Test public void invalidRangesRefuseBeforeSleep() {
        Clock clock=new Clock();AndroidSttPcmPacer p=new AndroidSttPcmPacer(AndroidSttPcmPacer.Mode.REALTIME_20MS,clock);
        assertThrows(IllegalArgumentException.class,()->p.beforeWrite(1,640,()->true));
        assertThrows(IllegalArgumentException.class,()->p.beforeWrite(0,641,()->true));
        assertThrows(IllegalArgumentException.class,()->p.beforeWrite(960000,2,()->true));
        assertEquals(0,clock.waits);
    }
    @Test public void nativeTotalsAndSealSnapshotSurviveEventOverflow() {
        AndroidSttTrace trace=new AndroidSttTrace(640);
        for(int i=0;i<100;i++)trace.event(AndroidSttTrace.Kind.CB_PARTIAL,0,3,false);
        trace.event(AndroidSttTrace.Kind.CB_RESULTS,0,7,false);
        trace.completionSnapshot(false,0,true);
        String json=trace.sealJson();
        assertTrue(json.contains("\"CB_PARTIAL\":{\"count\":100,\"chars\":300}"));
        assertTrue(json.contains("\"CB_RESULTS\":{\"count\":1,\"chars\":7}"));
        assertTrue(json.contains("\"completion_snapshot\":{\"seen\":true,\"success\":false,\"text_chars\":0,\"cleanup_confirmed\":true}"));
        assertTrue(json.length()<=AndroidSttTrace.MAX_JSON_BYTES);
        trace.event(AndroidSttTrace.Kind.CB_RESULTS,0,99,false);
        trace.completionSnapshot(true,99,false);assertEquals(json,trace.sealJson());
    }
}
