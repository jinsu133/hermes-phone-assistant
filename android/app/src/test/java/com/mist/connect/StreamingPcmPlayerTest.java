package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class StreamingPcmPlayerTest {
    static class Fake implements StreamingPcmPlayer.Sink, StreamingPcmPlayer.Clock {
        long now,head,written; int pauses,maxWrite; boolean aborted,closed,active=true;
        boolean stall=false, failWrite=false, stale=false, cancelOnPause=false;
        StreamingPcmPlayer player;
        public void start() {}
        public int write(byte[] b,int o,int n) {
            maxWrite=Math.max(maxWrite,n);
            if(failWrite) return -1;
            int count=Math.min(2,n); written+=count/2;return count;
        }
        public long playedFrames() {return head;}
        public void abort() {aborted=true;}
        public void close() {closed=true;}
        public long millis() {return now;}
        public void pause() {pauses++;now+=1000;if(!stall)head=written;if(stale)active=false;if(cancelOnPause)player.cancel();}
        StreamingPcmPlayer player() {
            player=new StreamingPcmPlayer("r","a",7,(r,a,g)->active,this,this);return player;
        }
    }
    static byte[] wire(long generation,int frames,long eos,boolean ending) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(0x48505331);d.writeUTF("r");d.writeUTF("a");d.writeLong(generation);
        d.writeByte(1);d.writeInt(frames*2);d.write(new byte[frames*2]);
        if(ending) {d.writeByte(2);d.writeInt(8);d.writeLong(eos);}return b.toByteArray();
    }
    static void reject(Fake f,byte[] b,String code) throws Exception {
        try {f.player().play(new ByteArrayInputStream(b));fail("receipt issued");}
        catch(IOException e) {if(code!=null)assertEquals(code,e.getMessage());}
        assertTrue(f.aborted);assertTrue(f.closed);
    }
    @Test public void writesAreNotPlaybackAndReceiptSingleUse() throws Exception {
        Fake f=new Fake();StreamingPcmPlayer p=f.player();
        StreamingPcmPlayer.Receipt receipt=p.play(new ByteArrayInputStream(wire(7,16,16,true)));
        assertEquals(16,receipt.frameCount);assertEquals(7,receipt.generation);
        assertEquals("r",receipt.requestId);assertEquals("a",receipt.audioId);
        assertEquals(1,f.pauses);assertTrue(f.closed);assertFalse(f.aborted);
        try {p.play(new ByteArrayInputStream(wire(7,16,16,true)));fail();}
        catch(IOException e){assertEquals("ALREADY_USED",e.getMessage());}
    }
    @Test public void slowDripChecksDeadlineInsideReadFully() throws Exception {
        Fake f=new Fake();byte[] b=wire(7,4,4,true);
        InputStream drip=new ByteArrayInputStream(b) {
            public synchronized int read() {f.now+=10000;return super.read();}
            public synchronized int read(byte[] a,int o,int n) {f.now+=10000;return super.read(a,o,Math.min(n,1));}
        };
        try {f.player().play(drip);fail();} catch(IOException e) {assertEquals("DEADLINE",e.getMessage());}
        assertTrue(f.aborted);assertTrue(f.closed);
    }
    @Test public void zeroWriteBackpressureDoesNotComplete() throws Exception {
        Fake f=new Fake() {
            int attempts;
            public int write(byte[] b,int o,int n) {return attempts++==0?0:super.write(b,o,n);}
        };
        StreamingPcmPlayer.Receipt r=f.player().play(new ByteArrayInputStream(wire(7,4,4,true)));
        assertEquals(4,r.frameCount);assertEquals(2,f.pauses);assertTrue(f.maxWrite<=8192);
    }
    @Test public void wrongGeneration() throws Exception {reject(new Fake(),wire(8,4,4,true),"IDENTITY");}
    @Test public void truncatedNoReceipt() throws Exception {reject(new Fake(),wire(7,4,4,false),null);}
    @Test public void mismatchedFrames() throws Exception {reject(new Fake(),wire(7,4,5,true),"EOS");}
    @Test public void errorRecord() throws Exception {
        byte[] b=wire(7,4,4,true);b[b.length-13]=3;reject(new Fake(),b,"FRAME");
    }
    @Test public void staleDuringDrain() throws Exception {Fake f=new Fake();f.stale=true;reject(f,wire(7,4,4,true),"CANCELLED");}
    @Test public void cancelledDuringDrain() throws Exception {Fake f=new Fake();f.cancelOnPause=true;reject(f,wire(7,4,4,true),"CANCELLED");}
    @Test public void deadlineNoPlayback() throws Exception {Fake f=new Fake();f.stall=true;reject(f,wire(7,4,4,true),"DEADLINE");}
    @Test public void failedSink() throws Exception {Fake f=new Fake();f.failWrite=true;reject(f,wire(7,4,4,true),"SINK_WRITE");}
    @Test public void oversizedRecord() throws Exception {reject(new Fake(),wire(7,4097,4097,true),"FRAME");}
    @Test public void trailingOrDuplicateEOS() throws Exception {
        byte[] b=wire(7,4,4,true);byte[] extra=java.util.Arrays.copyOf(b,b.length+1);
        reject(new Fake(),extra,"EOS");
    }
}
