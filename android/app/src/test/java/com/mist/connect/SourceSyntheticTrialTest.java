package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SourceSyntheticTrialTest {
    private SourceServicePlaybackAdapter owner(){return new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);}
    @Test public void explicitOptInRequired() {
        try{new SourceSyntheticTrial(false,1,"a".repeat(40),owner(),()->0);fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void stoppedBeforeRunNeverConnectsOrEffects() throws Exception {
        SourceServicePlaybackAdapter o=owner();
        SourceSyntheticTrial trial=new SourceSyntheticTrial(true,1,"a".repeat(40),o,()->0);
        trial.stop();
        try{trial.run();fail();}catch(IOException expected){assertEquals("trial_one_use",expected.getMessage());}
        assertFalse(trial.localEffect("old"));assertFalse(o.played(0));
    }
    @Test public void syntheticTransportBoundToExactRetainedOwner() {
        SourceServicePlaybackAdapter o=owner();
        SourceSyntheticTrial trial=new SourceSyntheticTrial(true,1,"a".repeat(40),o,()->0);
        assertTrue(trial.owns(o));assertFalse(trial.owns(owner()));
    }
    @Test public void monitorReservationDeniesNewGenerationUntilCleanup() {
        SourceServicePlaybackAdapter o=owner();assertTrue(o.begin(1,1));
        assertTrue(o.startMonitor(()->true,()->{}));
        assertFalse(o.begin(2,1));o.captureCleanup(CaptureCleanup.release(null,null,()->{}));
        assertTrue(o.begin(2,1));
    }
    @Test public void legacyStopAckDoesNotHoldOwnerGate() throws Exception {
        SourceServicePlaybackAdapter o=owner();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{
            try{o.stopAndAcknowledge(1000,()->{entered.countDown();
                try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("test_timeout");}
                catch(InterruptedException e){throw new IOException("interrupted");}
                return new byte[0];});}catch(Throwable e){error.set(e);}
        });
        worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            assertFalse(executor.submit(()->o.begin(1,1)).get(1,TimeUnit.SECONDS));
            executor.submit(o::off).get(1,TimeUnit.SECONDS);
        }finally{release.countDown();worker.join(2000);executor.shutdownNow();}
        assertFalse(worker.isAlive());assertNull(error.get());assertTrue(o.begin(1,1));
    }
    private void field(SourceSyntheticTrial trial,String key,Object value) throws Exception {
        java.lang.reflect.Field f=SourceSyntheticTrial.class.getDeclaredField(key);f.setAccessible(true);f.set(trial,value);
    }
    private SourceSyntheticTrial pending(SourceServicePlaybackAdapter o) throws Exception {
        assertTrue(o.begin(3,7));
        assertTrue(o.enqueue(3,0,"synthetic",SourceServicePlaybackAdapter.Kind.FINAL,()->new SourceServicePlaybackAdapter.Session(){
            public boolean play(){return true;}public void abort(){}public void close(){}
            public boolean sourceClosed(){return true;}public boolean sinkClosed(){return true;}
        }));
        assertTrue(o.startNext());assertTrue(o.awaitWorker(1000));assertTrue(o.played(3));
        SourceSyntheticTrial t=new SourceSyntheticTrial(true,1,"a".repeat(40),o,()->0);
        field(t,"ticket","accepted-host-ticket");field(t,"generation",3L);field(t,"revision",7L);
        return t;
    }
    @Test public void exactLocalTicketOneUseAtEffectBoundary() throws Exception {
        SourceServicePlaybackAdapter o=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE);
        SourceSyntheticTrial t=pending(o);
        assertFalse(t.localEffect("wrong"));assertTrue(t.localEffect("accepted-host-ticket"));
        o.endListening(3,true);assertFalse(t.localEffect("accepted-host-ticket"));
    }
    @Test public void offAfterHostAcknowledgementDeniesUnconsumedLocalTicket() throws Exception {
        SourceServicePlaybackAdapter o=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE);
        SourceSyntheticTrial t=pending(o);o.off();assertFalse(t.localEffect("accepted-host-ticket"));
    }
    @Test public void consentAfterHostAcknowledgementDeniesLocalTicket() throws Exception {
        SourceServicePlaybackAdapter o=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE);
        SourceSyntheticTrial t=pending(o);o.consentChanged(8);assertFalse(t.localEffect("accepted-host-ticket"));
    }
    @Test public void newGenerationDeniesHistoricalTicket() throws Exception {
        SourceServicePlaybackAdapter o=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE);
        SourceSyntheticTrial t=pending(o);assertTrue(o.begin(4,7));assertFalse(t.localEffect("accepted-host-ticket"));
    }
    @Test public void liveStillRefused() {
        try{new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.LIVE_SERVICE);fail();}
        catch(IllegalStateException expected){}
    }
}
