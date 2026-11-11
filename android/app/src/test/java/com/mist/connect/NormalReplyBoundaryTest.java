package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Hardware-free normal-service ordering, not a streaming or microphone acceptance test. */
public class NormalReplyBoundaryTest {
    private SourceServicePlaybackAdapter owner() {
        return new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
    }
    private void play(SourceServicePlaybackAdapter o, long epoch, SourceServicePlaybackAdapter.Kind kind) throws Exception {
        SourceBufferedPlaybackSession.Backend b = new SourceBufferedPlaybackSession.Backend() {
            Runnable done;
            public void prepare(byte[] bytes, Runnable complete, Runnable fail) { done=complete; }
            public void start() { done.run(); }
            public void abort() {}
            public void close() {}
        };
        assertTrue(o.enqueueBuffered(epoch,0,"normal_"+epoch,kind,new byte[]{1,2},b));
        assertTrue(o.startNext()); assertTrue(o.awaitWorker(2000)); assertTrue(o.played(epoch));
    }
    @Test public void finalCanPlayDuringStopMonitorButCannotOpenFollowupUntilMonitorReleased() throws Exception {
        var o=owner(); assertTrue(o.begin(1,7));
        assertTrue(o.startMonitor(()->true,()->{}));
        assertFalse(o.begin(2,7)); // Ordinary capture lifecycle still cannot replace an active monitor.
        assertTrue(o.beginPlaybackChecked(2,7,()->true));
        assertTrue(o.beginPlaybackChecked(3,7,()->true)); // Same two transitions as speakReplyParts -> playAudio.
        play(o,3,SourceServicePlaybackAdapter.Kind.FINAL);
        assertFalse(o.startMonitor(()->true,()->fail()));
        assertFalse(o.captureIdle(3,7,()->true,()->fail()));
        assertFalse(o.consumeListening(3,7,()->true,()->fail()));
        o.captureCleanup(CaptureCleanup.release(null,null,()->{}));
        AtomicInteger starts=new AtomicInteger();
        assertTrue(o.consumeListening(3,7,()->true,starts::incrementAndGet));
        assertEquals(1,starts.get());
        assertFalse(o.consumeListening(3,7,()->true,()->fail()));
        o.endListening(3,true);
    }
    @Test public void progressCannotGrantListeningAndFinalStillRequiresProgressJoin() throws Exception {
        var o=owner(); assertTrue(o.begin(1,7));
        assertTrue(o.startMonitor(()->true,()->{}));
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean();
        assertTrue(o.startProgress(()->{
            accepted.set(o.beginPlaybackChecked(2,7,()->true)); done.countDown();
        }));
        assertTrue(done.await(2,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(accepted.get());
        // Progress handle is retained even after the producer returns.
        assertFalse(o.beginPlaybackChecked(3,7,()->true));
        assertTrue(o.stopProgressAndJoin(2000));
        assertTrue(o.beginPlaybackChecked(3,7,()->true));
        play(o,3,SourceServicePlaybackAdapter.Kind.PROGRESS);
        o.captureCleanup(CaptureCleanup.release(null,null,()->{}));
        assertFalse(o.consumeListening(3,7,()->true,()->fail()));
    }
    @Test public void playbackTransitionNeverBypassesCaptureConsentOffOrCleanupPoison() {
        var o=owner(); assertTrue(o.begin(1,7));
        assertTrue(o.captureIdle(1,7,()->true,()->{}));
        assertFalse(o.beginPlaybackChecked(2,7,()->true));
        o.endListening(1,true);
        assertFalse(o.beginPlaybackChecked(2,7,()->false));
        assertFalse(o.beginPlaybackChecked(2,8,()->true));
        assertTrue(o.startMonitor(()->true,()->{}));
        o.captureCleanup(CaptureCleanup.release(null,null,()->{throw new IllegalStateException("PRIVATE_PAYLOAD");}));
        assertFalse(o.beginPlaybackChecked(2,7,()->true));
        assertTrue(o.blocked());
    }
    @Test public void cancelledProgressCanBeReplacedOnlyWithCurrentServicePredicate() throws Exception {
        var o=owner(); assertTrue(o.begin(1,7));
        assertTrue(o.beginPlaybackChecked(2,7,()->true));
        play(o,2,SourceServicePlaybackAdapter.Kind.PROGRESS);
        assertTrue(o.stopAndJoin(100));
        assertFalse(o.beginPlaybackChecked(3,7,()->false)); // actual OFF predicate
        assertTrue(o.beginPlaybackChecked(3,7,()->true)); // final still current after progress supersession
        play(o,3,SourceServicePlaybackAdapter.Kind.FINAL);
        o.off();
        assertFalse(o.consumeListening(3,7,()->true,()->fail()));
        assertFalse(o.beginPlaybackChecked(4,7,()->false));
    }
    @Test public void serviceDiagnosticsAreBoundedAndNeverStringifyFailures() {
        var o=owner();
        Throwable error=new IllegalStateException("PRIVATE_PAYLOAD",new java.io.IOException("TOKEN"));
        for(int i=0;i<100;i++) o.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.PROGRESS,error);
        String[] entries=o.diagnosticSnapshot();
        assertEquals(64,entries.length);
        for(String entry:entries) {
            assertTrue(entry.contains("stage=service_progress_failed"));
            assertFalse(entry.contains("PRIVATE_PAYLOAD")); assertFalse(entry.contains("TOKEN"));
        }
    }
}
