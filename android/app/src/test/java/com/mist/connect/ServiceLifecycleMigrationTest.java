package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** No hardware/network: exact production buffered mode and Session, injected backend only. */
public class ServiceLifecycleMigrationTest {
    static class Backend implements SourceBufferedPlaybackSession.Backend {
        final CountDownLatch started=new CountDownLatch(1), closed=new CountDownLatch(1);
        Runnable completed; boolean immediate=true, abortFails, closeFails;
        int stops, releases;
        public void prepare(byte[] b,Runnable complete,Runnable fail){completed=complete;}
        public void start(){started.countDown();if(immediate)completed.run();}
        public void abort(){stops++;if(abortFails)throw new IllegalStateException("stop");}
        public void close(){releases++;closed.countDown();if(closeFails)throw new IllegalStateException("release");}
    }
    SourceServicePlaybackAdapter owner(){return new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);}
    void submit(SourceServicePlaybackAdapter o,long epoch,Backend b) {
        assertTrue(o.enqueueBuffered(epoch,0,"b_"+epoch,SourceServicePlaybackAdapter.Kind.FINAL,new byte[]{1,2},b));
        assertTrue(o.startNext());
    }
    void complete(SourceServicePlaybackAdapter o)throws Exception {
        assertTrue(o.begin(1,7));submit(o,1,new Backend());assertTrue(o.awaitWorker(2000));assertTrue(o.played(1));
    }
    @Test public void realBufferedModeCannotUseArbitraryOrPcmFactories() {
        var o=owner();o.begin(1,0);
        AtomicBoolean allocated=new AtomicBoolean();
        try{o.enqueue(1,0,"pcm",SourceServicePlaybackAdapter.Kind.FINAL,()->{allocated.set(true);return null;});fail();}
        catch(IllegalStateException expected){assertEquals("buffered_factory_required",expected.getMessage());}
        assertFalse(allocated.get());
        try{new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.LIVE_SERVICE);fail();}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("lease"));}
    }
    @Test public void actualWorkerMustBeJoinedBeforeBufferedReuse()throws Exception {
        var o=owner();o.begin(1,0);Backend b=new Backend();submit(o,1,b);
        assertTrue(b.closed.await(1,TimeUnit.SECONDS));assertFalse(o.begin(2,0));
        assertTrue(o.awaitWorker(2000));assertTrue(o.begin(2,0));
    }
    @Test public void finalWaitsForTrackedProgressProducer()throws Exception {
        var o=owner();o.begin(1,0);CountDownLatch entered=new CountDownLatch(1),done=new CountDownLatch(1);
        assertTrue(o.startProgress(()->{entered.countDown();try{done.await();}catch(InterruptedException e){}}));
        assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(o.begin(2,0));
        assertFalse(o.startProgress(()->fail()));assertTrue(o.stopProgressAndJoin(2000));
        assertTrue(o.begin(2,0));submit(o,2,new Backend());assertTrue(o.awaitWorker(2000));assertTrue(o.played(2));
    }
    @Test public void progressMayUseSameBufferedOwnerButNotASecondOwner()throws Exception {
        var o=owner();o.begin(1,0);AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        assertTrue(o.startProgress(()->{try{
            assertTrue(o.begin(2,0));submit(o,2,new Backend());assertTrue(o.awaitWorker(2000));
        }catch(Throwable t){failure.set(t);}finally{done.countDown();}}));
        assertTrue(done.await(3,TimeUnit.SECONDS));assertTrue(o.stopProgressAndJoin(2000));
        if(failure.get()!=null)throw new AssertionError(failure.get());assertTrue(o.begin(3,0));
    }
    @Test public void unjoinedProgressPoisonsRatherThanReusing()throws Exception {
        var o=owner();o.begin(1,0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        assertTrue(o.startProgress(()->{entered.countDown();while(release.getCount()!=0)try{release.await();}catch(InterruptedException e){}}));
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        try{assertFalse(o.stopProgressAndJoin(1));assertTrue(o.blocked());assertFalse(o.begin(2,0));}
        finally{release.countDown();o.stopProgressAndJoin(2000);}
    }
    @Test public void releaseFailurePermanentlyBlocksActualBufferedPath()throws Exception {
        var o=owner();o.begin(1,0);Backend b=new Backend();b.closeFails=true;submit(o,1,b);assertTrue(o.awaitWorker(2000));
        assertTrue(o.blocked());assertFalse(o.played(1));assertFalse(o.begin(2,0));assertFalse(o.stopAndJoin(100));assertEquals(1,b.releases);
    }
    @Test public void cancellationAbortFailureStillAttemptsReleaseAndBlocks()throws Exception {
        var o=owner();o.begin(1,0);Backend b=new Backend();b.immediate=false;b.abortFails=true;submit(o,1,b);
        assertTrue(b.started.await(1,TimeUnit.SECONDS));o.off();assertTrue(o.awaitWorker(2000));
        assertTrue(o.blocked());assertEquals(1,b.releases);assertFalse(o.consumeListening(1,0,()->fail()));
    }
    @Test public void cancelClosesThenJoinAllowsNewGeneration()throws Exception {
        var o=owner();o.begin(1,0);Backend b=new Backend();b.immediate=false;submit(o,1,b);
        assertTrue(b.started.await(1,TimeUnit.SECONDS));assertTrue(o.stopAndJoin(2000));assertEquals(1,b.releases);
        assertFalse(o.consumeListening(1,0,()->fail()));assertTrue(o.begin(2,1));
    }
    @Test public void currentSessionConsentAndOwnerCheckedAtActualStart()throws Exception {
        var o=owner();complete(o);AtomicInteger starts=new AtomicInteger();AtomicBoolean sameSession=new AtomicBoolean(false);
        assertFalse(o.consumeListening(1,7,sameSession::get,starts::incrementAndGet));
        assertFalse(o.consumeListening(1,8,()->true,starts::incrementAndGet));
        sameSession.set(true);assertTrue(o.consumeListening(1,7,sameSession::get,starts::incrementAndGet));
        assertEquals(1,starts.get());assertFalse(o.begin(2,7)); // Capture reservation excludes new playback.
        o.endListening(1);assertTrue(o.begin(2,7));
        assertFalse(o.consumeListening(1,7,()->true,starts::incrementAndGet));
    }
    @Test public void offAfterTicketBeforeCaptureRejectsFollowup()throws Exception {
        var o=owner();complete(o);o.off();assertFalse(o.consumeListening(1,7,()->true,()->fail()));
    }
    @Test public void destroyAfterTicketRejectsFollowupAndReuse()throws Exception {
        var o=owner();complete(o);o.destroy();assertFalse(o.consumeListening(1,7,()->true,()->fail()));assertFalse(o.begin(2,7));
    }
    @Test public void consentChangeRejectsHistoricalCompletion()throws Exception {
        var o=owner();complete(o);o.consentChanged(8);assertFalse(o.consumeListening(1,7,()->true,()->fail()));
    }
    @Test public void startFailurePoisonsAndCannotBeRetried()throws Exception {
        var o=owner();complete(o);assertFalse(o.consumeListening(1,7,()->true,()->{throw new IllegalStateException();}));
        assertTrue(o.blocked());o.endListening(1);assertFalse(o.begin(2,7));
    }
    @Test public void offAndStartEffectSerializeWithoutSnapshotPermission()throws Exception {
        var o=owner();complete(o);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),offDone=new CountDownLatch(1);
        AtomicBoolean allowed=new AtomicBoolean(true),ok=new AtomicBoolean();
        Thread start=new Thread(()->ok.set(o.consumeListening(1,7,allowed::get,()->{
            entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
        })));
        start.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        Thread off=new Thread(()->{o.offAnd(()->allowed.set(false));offDone.countDown();});off.start();
        assertFalse(offDone.await(20,TimeUnit.MILLISECONDS));release.countDown();start.join(2000);off.join(2000);
        assertFalse(start.isAlive() || off.isAlive());assertTrue(ok.get());assertFalse(allowed.get());
        assertFalse(o.beginChecked(2,8,allowed::get));o.endListening(1);
        assertFalse(o.captureIdle(1,7,()->true,()->fail()));
    }
    @Test public void simultaneousProgressJoinersAgreeOnSameCleanup()throws Exception {
        var o=owner();o.begin(1,0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        assertTrue(o.startProgress(()->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException e){}}));
        assertTrue(entered.await(1,TimeUnit.SECONDS));AtomicInteger joined=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        Runnable join=()->{try{if(o.stopProgressAndJoin(2000))joined.incrementAndGet();}catch(Throwable t){failure.set(t);}};
        Thread a=new Thread(join),b=new Thread(join);a.start();b.start();release.countDown();a.join(3000);b.join(3000);
        assertFalse(a.isAlive() || b.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
        assertEquals(2,joined.get());assertFalse(o.blocked());assertTrue(o.begin(2,0));
    }
    @Test public void repeatedOffDoesNotInterruptProgressCleanupAgain()throws Exception {
        var o=owner();o.begin(1,0);CountDownLatch entered=new CountDownLatch(1),cleaning=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicBoolean secondInterrupt=new AtomicBoolean();
        assertTrue(o.startProgress(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){
            cleaning.countDown();try{release.await();}catch(InterruptedException again){secondInterrupt.set(true);}
        }finally{done.countDown();}}));
        assertTrue(entered.await(1,TimeUnit.SECONDS));o.off();assertTrue(cleaning.await(1,TimeUnit.SECONDS));
        o.off();o.destroy();release.countDown();assertTrue(done.await(1,TimeUnit.SECONDS));
        assertTrue(o.stopAndJoin(2000));assertFalse(secondInterrupt.get());
    }
    @Test public void interruptedProgressJoinPoisonsOwner()throws Exception {
        var o=owner();o.begin(1,0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        assertTrue(o.startProgress(()->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException e){}}));
        assertTrue(entered.await(1,TimeUnit.SECONDS));Thread.currentThread().interrupt();
        try{o.stopProgressAndJoin(2000);fail();}catch(InterruptedException expected){assertTrue(o.blocked());}
        finally{Thread.interrupted();release.countDown();o.stopProgressAndJoin(2000);}
        assertFalse(o.begin(2,0));
    }
    @Test public void idleCaptureReservationAndOffBlockPlaybackAndMonitor()throws Exception {
        var o=owner();assertTrue(o.begin(1,0));assertTrue(o.captureIdle(1,0,()->true,()->{}));
        assertFalse(o.begin(2,0));o.off();assertFalse(o.startMonitor(()->true,()->fail()));
        assertFalse(o.stopAndJoin(100));o.endListening(1);assertTrue(o.stopAndJoin(100));
    }
}
