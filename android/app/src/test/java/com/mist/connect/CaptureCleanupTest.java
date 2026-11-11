package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CaptureCleanupTest {
    private void failureCase(boolean stopFails, boolean aecFails, boolean micFails) throws Exception {
        var owner=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        assertTrue(owner.begin(1,0));
        assertTrue(owner.captureIdle(1,0,()->true,()->{}));
        List<String> attempts=new ArrayList<>();
        Throwable primary=new IllegalArgumentException("never-log-primary-secret");
        Throwable stop=new IllegalStateException("never-log-stop"), aec=new AssertionError("never-log-aec"), mic=new IllegalStateException("never-log-mic");
        var result=CaptureCleanup.release(
            ()->{attempts.add("stop");if(stopFails)throw stop;},
            ()->{attempts.add("aec");if(aecFails)throw aec;},
            ()->{attempts.add("mic");if(micFails)throw mic;});
        assertEquals(Arrays.asList("stop","aec","mic"),attempts);
        assertFalse(result.confirmed());
        assertSame(primary,result.preserve(primary));
        List<Throwable> expected=new ArrayList<>();
        if(stopFails)expected.add(stop);if(aecFails)expected.add(aec);if(micFails)expected.add(mic);
        assertArrayEquals(expected.toArray(new Throwable[0]),primary.getSuppressed());
        owner.captureCleanup(result);
        owner.endListening(1,result.confirmed());
        owner.endListening(1); // cannot accidentally reset sticky uncertainty
        assertTrue(owner.blocked());
        var field=SourceServicePlaybackAdapter.class.getDeclaredField("listening");field.setAccessible(true);
        assertTrue((Boolean)field.get(owner));
        assertFalse(owner.begin(2,0));assertFalse(owner.stopAndJoin(50));
        assertFalse(owner.startMonitor(()->true,()->fail()));
        assertFalse(owner.captureIdle(1,0,()->true,()->fail()));
        assertFalse(owner.enqueueBuffered(1,1,"later",SourceServicePlaybackAdapter.Kind.FINAL,new byte[]{1}));
        String diagnostics=String.join("\n",owner.diagnosticSnapshot());
        assertTrue(diagnostics.contains("owner_local="));assertTrue(diagnostics.contains("request_local="));
        assertTrue(diagnostics.contains("audio_local="));assertTrue(diagnostics.contains("generation=1"));assertTrue(diagnostics.contains("mono_ns="));
        assertTrue(diagnostics.contains("stage=aec_release"));assertTrue(diagnostics.contains("stage=mic_release"));
        assertFalse(diagnostics.contains("never-log"));
    }
    @Test public void aecErrorStillReleasesMicAndPoisonsSameOwner()throws Exception{failureCase(false,true,false);}
    @Test public void micErrorRetainsReservation()throws Exception{failureCase(false,false,true);}
    @Test public void stopErrorStillAttemptsBothReleases()throws Exception{failureCase(true,false,false);}
    @Test public void allFailuresRemainAttachedToPrimary()throws Exception{failureCase(true,true,true);}
    @Test public void cleanCleanupAllowsNextCapture()throws Exception{
        var owner=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        owner.begin(1,0);assertTrue(owner.captureIdle(1,0,()->true,()->{}));
        var result=CaptureCleanup.release(()->{},null,()->{});
        assertTrue(result.confirmed());assertNull(result.preserve(null));
        owner.captureCleanup(result);owner.endListening(1,result.confirmed());
        assertTrue(owner.stopAndJoin(50));assertTrue(owner.begin(2,0));
    }
    @Test public void selfSuppressionNeverReplacesPrimary(){
        Throwable primary=new IllegalStateException();
        var result=CaptureCleanup.release(()->{throw primary;},null,()->{throw primary;});
        assertSame(primary,result.preserve(primary));assertEquals(0,primary.getSuppressed().length);
    }
    @Test public void firstCleanupFailureBecomesPrimaryWhenNoReadError(){
        Throwable first=new IllegalStateException(),second=new AssertionError();
        var result=CaptureCleanup.release(null,()->{throw first;},()->{throw second;});
        assertSame(first,result.preserve(null));assertArrayEquals(new Throwable[]{second},first.getSuppressed());
    }
    @Test public void offDuringPrepareNeverStartsLatePlayback()throws Exception{
        var owner=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        CountDownLatch preparing=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger starts=new AtomicInteger();
        var backend=new SourceBufferedPlaybackSession.Backend(){
            public void prepare(byte[] bytes,Runnable completed,Runnable failed)throws Exception{
                preparing.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("test timeout");
            }
            public void start(){starts.incrementAndGet();}
            public void abort(){}
            public void close(){}
        };
        owner.begin(1,0);owner.enqueueBuffered(1,0,"late",SourceServicePlaybackAdapter.Kind.FINAL,new byte[]{1},backend);owner.startNext();
        try{assertTrue(preparing.await(1,TimeUnit.SECONDS));owner.offAnd(()->{});assertFalse(owner.begin(2,0));}
        finally{release.countDown();}
        assertTrue(owner.stopAndJoin(2000));assertEquals(0,starts.get());
    }
}
