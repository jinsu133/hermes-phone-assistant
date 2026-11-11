package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NormalControllerCancellationTest {
    static final class Transport implements SourceControllerTransport {
        public Connection connect(){return new Connection("p","s","h","d",1,"c",0);}
        public double heartbeat(Connection c){return 30;}
        public void stopAck(Connection c){}
        public void cancel(Connection c){}
    }
    @Test public void stopWithMonitorRetainsLeaseButNotCapturePermission()throws Exception {
        var owner=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        AtomicInteger revoked=new AtomicInteger();Transport transport=new Transport();
        owner.requireController(revoked::incrementAndGet);
        assertTrue(owner.installController(transport.connect(),3000));
        assertTrue(owner.acceptHeartbeat(owner.beginHeartbeat(),30));
        assertTrue(owner.acknowledgeController(transport));assertTrue(owner.begin(1,1));
        assertTrue(owner.startMonitor(()->true,()->{}));
        owner.cancelConversation(()->{});
        assertTrue(owner.cancelUtteranceAndJoin(100));assertTrue(owner.controllerCurrent());assertEquals(0,revoked.get());
        assertFalse(owner.begin(2,1));
        assertFalse(owner.captureIdle(1,1,()->true,()->fail()));
        owner.captureCleanup(CaptureCleanup.release(()->{},null,()->{}));
        assertTrue(owner.begin(2,1));assertTrue(owner.captureIdle(2,1,()->true,()->{}));
        owner.endListening(2,true);owner.off();assertFalse(owner.controllerCurrent());assertEquals(1,revoked.get());
    }
    @Test public void normalAdmissionNeedsRealControllerNotModeBoolean() {
        var owner=new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE);
        assertTrue(owner.begin(1,1));
        assertFalse(owner.enqueueNormal(1,"a",SourceServicePlaybackAdapter.Kind.FINAL,()->{throw new AssertionError();}));
    }
}
