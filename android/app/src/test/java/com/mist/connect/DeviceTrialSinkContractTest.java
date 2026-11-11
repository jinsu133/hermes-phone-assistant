package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;

/** No AudioTrack or microphone. Guards startup-threshold deadlock protocol. */
public class DeviceTrialSinkContractTest {
    private static final class Sink implements StreamingPcmPlayer.Sink {
        int next; boolean start, abort, close;
        public void start(){start=true;}
        public int write(byte[] b,int o,int n){return next;}
        public long playedFrames(){return 0;}
        public void abort(){abort=true;}
        public void close(){close=true;}
    }
    @Test public void acknowledgePositiveWriteWithoutWaitingForPlaybackHead() throws Exception {
        Sink raw=new Sink(); CountDownLatch first=new CountDownLatch(1);
        Class<?> type=Class.forName("com.mist.connect.SourceSyntheticTrial$ObservedSink");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(StreamingPcmPlayer.Sink.class,CountDownLatch.class);
        constructor.setAccessible(true);
        StreamingPcmPlayer.Sink sink=(StreamingPcmPlayer.Sink)constructor.newInstance(raw,first);
        sink.start();assertTrue(raw.start);
        raw.next=0;assertEquals(0,sink.write(new byte[8],0,8));assertEquals(1,first.getCount());
        raw.next=-1;assertEquals(-1,sink.write(new byte[8],0,8));assertEquals(1,first.getCount());
        raw.next=2;assertEquals(2,sink.write(new byte[8],0,8));assertEquals(0,first.getCount());
        assertEquals(0,sink.playedFrames());
        sink.abort();sink.close();assertTrue(raw.abort);assertTrue(raw.close);
    }
}
