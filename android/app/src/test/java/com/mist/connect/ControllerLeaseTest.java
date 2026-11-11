package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ControllerLeaseTest {
    private static SourceControllerTransport.Connection c(long epoch) {
        return new SourceControllerTransport.Connection("private_profile","private_session"+epoch,"private_host","private_device",epoch,"private_challenge"+epoch,epoch-1);
    }
    private static final class Transport implements SourceControllerTransport {
        int acks; boolean fail; Runnable onAck=()->{};
        public Connection connect(){return c(1);}
        public double heartbeat(Connection c) throws IOException {if(fail)throw new IOException("secret failure");return 3;}
        public void stopAck(Connection c){acks++;onAck.run();}
        public void cancel(Connection c){}
    }
    private final AtomicLong now=new AtomicLong(100);
    private final AtomicInteger revoked=new AtomicInteger();
    private SourceServicePlaybackAdapter owner() {
        SourceServicePlaybackAdapter o=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE,now::get);
        o.requireController(revoked::incrementAndGet);assertTrue(o.installController(c(1),3000));return o;
    }
    private void ready(SourceServicePlaybackAdapter o) throws Exception {
        assertTrue(o.acceptHeartbeat(o.beginHeartbeat(),3));assertTrue(o.acknowledgeController(new Transport()));
    }
    @Test public void notAuthorizedUntilHeartbeatAndAck() throws Exception {
        SourceServicePlaybackAdapter o=owner();assertFalse(o.begin(1,1));
        assertTrue(o.acceptHeartbeat(o.beginHeartbeat(),3));assertFalse(o.begin(1,1));
        assertTrue(o.acknowledgeController(new Transport()));assertTrue(o.begin(1,1));
    }
    @Test public void exactlyOneOutstandingHeartbeat() {
        SourceServicePlaybackAdapter o=owner();ControllerLease.Flight f=o.beginHeartbeat();
        assertNotNull(f);assertNull(o.beginHeartbeat());assertTrue(o.acceptHeartbeat(f,3));assertFalse(o.acceptHeartbeat(f,3));
    }
    @Test public void delayedResponseUsesSendNotReceiveAndEqualityFails() {
        SourceServicePlaybackAdapter o=owner();ControllerLease.Flight f=o.beginHeartbeat();now.set(1100);
        assertFalse(o.acceptHeartbeat(f,1));assertFalse(o.begin(1,1));assertEquals(1,revoked.get());
    }
    @Test public void acceptedResponseStillExpiresAtSendBudget() throws Exception {
        SourceServicePlaybackAdapter o=owner();ControllerLease.Flight f=o.beginHeartbeat();now.set(1100);
        assertTrue(o.acceptHeartbeat(f,3));assertTrue(o.acknowledgeController(new Transport()));assertTrue(o.begin(1,1));
        now.set(3100);assertFalse(o.captureIdle(1,1,()->true,()->fail("late microphone")));
        assertFalse(o.controllerCurrent());assertEquals(1,revoked.get());
    }
    @Test public void sleepJumpRevokesMonitorBeforeTimer() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);assertTrue(o.begin(1,1));now.addAndGet(60000);
        assertFalse(o.startMonitor(()->true,()->fail("sleep microphone")));assertNull(o.beginHeartbeat());
    }
    @Test public void offAndDestroyRejectPendingResponses() throws Exception {
        SourceServicePlaybackAdapter o=owner();ControllerLease.Flight f=o.beginHeartbeat();o.off();assertFalse(o.acceptHeartbeat(f,3));
        assertTrue(o.installController(c(2),3000));f=o.beginHeartbeat();o.destroy();assertFalse(o.acceptHeartbeat(f,3));
    }
    @Test public void staleResponseDoesNotKillFreshConnection() throws Exception {
        SourceServicePlaybackAdapter o=owner();ControllerLease.Flight old=o.beginHeartbeat();o.off();
        assertTrue(o.installController(c(2),3000));ready(o);assertFalse(o.acceptHeartbeat(old,3));assertTrue(o.controllerCurrent());
    }
    @Test public void noSameSessionOrOldDeviceReconnect() {
        SourceServicePlaybackAdapter o=owner();o.off();assertFalse(o.installController(c(1),3000));
        assertFalse(o.installController(new SourceControllerTransport.Connection("p","new","private_host","other_device",2,"c",1),3000));
    }
    @Test public void invalidBudgetsAndOverflowFailClosed() {
        for(double n:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,61,0.0001}){
            SourceServicePlaybackAdapter o=owner();assertFalse(o.acceptHeartbeat(o.beginHeartbeat(),n));assertFalse(o.controllerCurrent());
        }
        ControllerLease l=new ControllerLease(()->Long.MAX_VALUE-10);
        try{l.install(c(1),100);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void heartbeatDoesNotAcknowledgeOrRestoreOff() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);assertTrue(o.begin(1,1));o.off();
        assertNull(o.beginHeartbeat());assertFalse(o.begin(2,1));
    }
    @Test public void ackRunsOutsideGateAndBlocksConcurrentBeginAndOldAck() throws Exception {
        SourceServicePlaybackAdapter o=owner();assertTrue(o.acceptHeartbeat(o.beginHeartbeat(),3));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Transport t=new Transport();t.onAck=()->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}};
        AtomicBoolean accepted=new AtomicBoolean(true);
        Thread worker=new Thread(()->{try{accepted.set(o.acknowledgeController(t));}catch(Exception e){throw new RuntimeException(e);}});
        worker.start();assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(o.begin(1,1));
        o.off();assertFalse(o.installController(c(2),3000));release.countDown();worker.join(2000);
        assertFalse(worker.isAlive());assertFalse(accepted.get());assertTrue(o.installController(c(2),3000));assertFalse(o.begin(1,1));
    }
    @Test public void uncertainCaptureNeverAcksEvenWithFreshChallenge() throws Exception {
        SourceServicePlaybackAdapter o=owner();o.captureCleanup(CaptureCleanup.release(null,null,()->{throw new IOException("secret");}));
        assertFalse(o.stopAndJoin(100));assertFalse(o.installController(c(2),3000));
        Transport t=new Transport();assertFalse(o.acknowledgeController(t));assertEquals(0,t.acks);
    }
    @Test public void trackedMonitorBlocksAckUntilConfirmedRelease() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);assertTrue(o.begin(1,1));
        assertTrue(o.startMonitor(()->true,()->{}));assertFalse(o.stopAndJoin(100));
        o.captureCleanup(CaptureCleanup.release(null,null,()->{}));assertTrue(o.stopAndJoin(100));
        assertTrue(o.installController(c(2),3000));ready(o);
    }
    @Test public void monitorOwnsCaptureUntilCleanup() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);assertTrue(o.begin(1,1));
        assertTrue(o.startMonitor(()->true,()->{}));
        assertFalse(o.startMonitor(()->true,()->fail()));
        assertFalse(o.captureIdle(1,1,()->true,()->fail()));
        o.captureCleanup(CaptureCleanup.release(null,null,()->{}));
        assertTrue(o.captureIdle(1,1,()->true,()->{}));
        assertFalse(o.startMonitor(()->true,()->fail()));o.endListening(1,true);
    }
    @Test public void authFailureRevokesWithoutRetry() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);Transport t=new Transport();t.fail=true;
        SourceControllerDriver driver=new SourceControllerDriver(o,t,now::get,3000);
        try{driver.renew();fail();}catch(IOException expected){}
        assertFalse(o.controllerCurrent());assertNull(o.beginHeartbeat());
    }
    @Test public void diagnosticNeverIncludesRawScopeOrExceptionName() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);o.off();String events=String.join("\n",o.diagnosticSnapshot());
        assertTrue(events.contains("lease_elapsed_ms="));assertTrue(events.contains("connection_local=1"));
        assertFalse(events.contains("private_"));assertFalse(events.contains("challenge"));
    }
    @Test public void exactHeartbeatJsonRejectsMalformedAndWrongTypes() throws Exception {
        assertEquals(30,SourceLoopbackControllerTransport.heartbeatResponse(bytes("{\"status\":\"renewed\",\"lease_seconds\":30.0}")),0);
        for(String s:new String[]{"{\"status\":\"renewed\",\"lease_seconds\":true}",
                "{\"status\":\"renewed\",\"lease_seconds\":\"30\"}","{\"status\":\"renewed\",\"lease_seconds\":1e999}",
                "{\"status\":\"renewed\",\"lease_seconds\":30,\"extra\":1}","{\"status\":\"renewed\",\"lease_seconds\":30,\"lease_seconds\":20}",
                "{\"status\":\"renewed\",\"lease_seconds\":30} trailing","{\"status\":\"renewed\",\"lease_seconds\":NaN}"}) {
            try{SourceLoopbackControllerTransport.heartbeatResponse(bytes(s));fail(s);}catch(IOException expected){}
        }
    }
    @Test public void integerScopeIsTypeStrict() throws Exception {
        for(String n:new String[]{"true","1.0","1e0","9223372036854775808"}) {
            try{SourceControllerJson.integer(SourceControllerJson.parse(bytes("{\"epoch\":"+n+"}")),"epoch");fail();}catch(IOException expected){}
        }
    }
    @Test public void latePreparedPlaybackCannotStartAfterSleep() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);assertTrue(o.begin(1,1));
        CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger starts=new AtomicInteger();
        SourceServicePlaybackAdapter.Session session=new SourceServicePlaybackAdapter.Session(){
            boolean closed;
            public boolean play(){throw new AssertionError("unguarded");}
            public boolean playGuarded(SourceServicePlaybackAdapter.StartGate gate) throws Exception {
                prepared.countDown();release.await(2,TimeUnit.SECONDS);return gate.start(starts::incrementAndGet);
            }
            public void abort(){} public void close(){closed=true;}
            public boolean sourceClosed(){return closed;}public boolean sinkClosed(){return closed;}
        };
        assertTrue(o.enqueue(1,0,"audio",SourceServicePlaybackAdapter.Kind.FINAL,()->session));assertTrue(o.startNext());
        assertTrue(prepared.await(1,TimeUnit.SECONDS));now.addAndGet(60000);release.countDown();
        // Expiry may register its tracked abort worker after the first join snapshot.
        if(!o.awaitWorker(2000))assertTrue(o.awaitWorker(2000));assertEquals(0,starts.get());assertFalse(o.played(1));assertEquals(1,revoked.get());
    }
    @Test public void blockedCloseWithholdsNewConnectionAndAck() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);assertTrue(o.begin(1,1));
        CountDownLatch closing=new CountDownLatch(1),release=new CountDownLatch(1);
        SourceServicePlaybackAdapter.Session session=new SourceServicePlaybackAdapter.Session(){
            boolean closed;
            public boolean play(){return true;}public void abort(){}
            public void close() throws Exception {closing.countDown();release.await(2,TimeUnit.SECONDS);closed=true;}
            public boolean sourceClosed(){return closed;}public boolean sinkClosed(){return closed;}
        };
        o.enqueue(1,0,"audio",SourceServicePlaybackAdapter.Kind.FINAL,()->session);o.startNext();
        assertTrue(closing.await(1,TimeUnit.SECONDS));assertFalse(o.stopAndJoin(10));
        assertFalse(o.installController(c(2),3000));Transport t=new Transport();assertFalse(o.acknowledgeController(t));assertEquals(0,t.acks);
        release.countDown();assertTrue(o.stopAndJoin(2000));assertTrue(o.installController(c(2),3000));ready(o);
    }
    @Test public void connectResponseAfterOffCannotInstall() {
        SourceServicePlaybackAdapter o=owner();o.off();long attempt=o.beginConnectionAttempt();o.off();
        assertFalse(o.installController(c(2),3000,attempt));assertFalse(o.controllerCurrent());
    }
    @Test public void consentChangeRejectsRenewalAndCapture() throws Exception {
        SourceServicePlaybackAdapter o=owner();ready(o);o.begin(1,1);ControllerLease.Flight f=o.beginHeartbeat();
        o.consentChanged(2);assertFalse(o.acceptHeartbeat(f,3));assertFalse(o.captureIdle(1,2,()->true,()->fail()));
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
}
