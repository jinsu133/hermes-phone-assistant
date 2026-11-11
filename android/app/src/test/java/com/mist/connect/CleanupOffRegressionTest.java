package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Hardware-free regression of the production buffered owner and session. */
public class CleanupOffRegressionTest {
    @Test public void blockedNativeCloseCannotDelayLogicalOff() throws Exception {
        var owner = new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        CountDownLatch closing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean revoked = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var backend = new SourceBufferedPlaybackSession.Backend() {
            Runnable complete;
            public void prepare(byte[] bytes, Runnable done, Runnable failed) {complete=done;}
            public void start() {complete.run();}
            public void abort() {}
            public void close() {
                closing.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("test timeout");}
                catch(InterruptedException e){throw new AssertionError(e);}
            }
        };
        owner.begin(1,0);owner.enqueueBuffered(1,0,"close",SourceServicePlaybackAdapter.Kind.FINAL,new byte[]{1},backend);owner.startNext();
        assertTrue(closing.await(2,TimeUnit.SECONDS));
        Thread off = new Thread(()->{try{owner.offAnd(()->revoked.set(true));}catch(Throwable t){failure.set(t);}});
        off.start();
        try {
            off.join(1000);assertFalse(off.isAlive());assertTrue(revoked.get());
            assertFalse(owner.begin(2,1));assertFalse(owner.stopAndJoin(10));
            assertFalse(owner.startMonitor(()->true,()->fail()));
        } finally {release.countDown();off.join(3000);}
        if(failure.get()!=null)throw new AssertionError(failure.get());
        assertTrue(owner.stopAndJoin(2000));
    }

    @Test public void uncertainCleanupCannotEraseListeningReservation() throws Exception {
        var owner = new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        assertTrue(owner.begin(1, 0));
        assertTrue(owner.captureIdle(1, 0, () -> true, () -> {}));
        owner.failCleanup();
        owner.endListening(1); // legacy callers must not erase poison's reservation
        var field = SourceServicePlaybackAdapter.class.getDeclaredField("listening");
        field.setAccessible(true);
        assertTrue((Boolean) field.get(owner));
        assertFalse(owner.stopAndJoin(50));
        assertFalse(owner.begin(2, 1));
    }

    @Test public void offRevokesBeforeBlockedNativeAbortAndDeniesConcurrentEffects() throws Exception {
        var owner = new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        CountDownLatch started = new CountDownLatch(1), abortEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean revoked = new AtomicBoolean(), offReturned = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        var backend = new SourceBufferedPlaybackSession.Backend() {
            public void prepare(byte[] data, Runnable complete, Runnable failed) {}
            public void start() { started.countDown(); }
            public void abort() {
                abortEntered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test barrier timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            public void close() {}
        };
        assertTrue(owner.begin(1, 0));
        assertTrue(owner.enqueueBuffered(1, 0, "bounded", SourceServicePlaybackAdapter.Kind.FINAL, new byte[]{1}, backend));
        assertTrue(owner.startNext());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread off = new Thread(() -> { try { owner.offAnd(() -> revoked.set(true)); offReturned.set(true); }
            catch (Throwable t) { error.set(t); } });
        off.start();
        try {
            assertTrue(abortEntered.await(2, TimeUnit.SECONDS));
            assertTrue("logical revoke must precede native abort", revoked.get());
            off.join(1000);
            assertTrue("OFF does not wait for native teardown", offReturned.get());
            assertFalse(owner.begin(2, 1));
            assertFalse(owner.captureIdle(1, 0, () -> true, () -> fail("capture crossed OFF")));
            assertFalse(owner.startMonitor(() -> true, () -> fail("monitor crossed OFF")));
            assertFalse(owner.startNext());
            assertFalse(owner.stopAndJoin(10));
        } finally { release.countDown(); off.join(3000); }
        assertFalse(off.isAlive());
        if (error.get() != null) throw new AssertionError(error.get());
        assertTrue(owner.stopAndJoin(2000));
    }
}
