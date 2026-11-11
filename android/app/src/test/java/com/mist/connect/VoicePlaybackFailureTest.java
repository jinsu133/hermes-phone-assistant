package com.mist.connect;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoicePlaybackFailureTest {
    @Test public void primaryAndAllIndependentCleanupAreTypedWithoutPayload()throws Exception{
        StreamingPcmPlayerTest.Fake sink=new StreamingPcmPlayerTest.Fake(){
            public int write(byte[] b,int o,int n){throw new IllegalStateException("PRIVATE_WRITE");}
            public void abort(){aborted=true;throw new IllegalStateException("PRIVATE_ABORT");}
            public void close(){closed=true;throw new IllegalStateException("PRIVATE_CLOSE");}
        };
        InputStream source=new ByteArrayInputStream(StreamingPcmPlayerTest.wire(7,4,4,true)){
            public void close()throws IOException{throw new IOException("PRIVATE_SOURCE");}
        };
        try{sink.player().play(source);fail("receipt");}
        catch(VoicePlaybackFailure f){
            assertEquals(VoicePlaybackFailure.Stage.SINK_WRITE,f.stage);
            assertEquals(VoicePlaybackFailure.Category.SINK_WRITE,f.category);
            assertEquals(3,f.getSuppressed().length);assertNull(f.getCause());
            assertEquals(VoicePlaybackFailure.Stage.SOURCE_CLOSE,((VoicePlaybackFailure)f.getSuppressed()[0]).stage);
            assertEquals(VoicePlaybackFailure.Stage.SINK_ABORT,((VoicePlaybackFailure)f.getSuppressed()[1]).stage);
            assertEquals(VoicePlaybackFailure.Stage.SINK_CLOSE,((VoicePlaybackFailure)f.getSuppressed()[2]).stage);
            StringWriter text=new StringWriter();f.printStackTrace(new PrintWriter(text));
            assertFalse(text.toString().contains("PRIVATE_"));
        }
        assertTrue(sink.aborted);assertTrue(sink.closed);
    }
    @Test public void sourceCloseAfterHeadCannotIssueReceipt()throws Exception{
        StreamingPcmPlayerTest.Fake sink=new StreamingPcmPlayerTest.Fake();
        try{sink.player().play(new ByteArrayInputStream(StreamingPcmPlayerTest.wire(7,4,4,true)){
            public void close()throws IOException{throw new IOException("PRIVATE_CLOSE");}
        });fail("receipt");}catch(VoicePlaybackFailure f){assertEquals(VoicePlaybackFailure.Stage.SOURCE_CLOSE,f.stage);}
        assertTrue(sink.closed);assertTrue(sink.aborted);
    }
    @Test public void interruptedExceptionContractAndSafeStageSurvive()throws Exception{
        StreamingPcmPlayerTest.Fake sink=new StreamingPcmPlayerTest.Fake();
        StreamingPcmPlayer.Clock clock=new StreamingPcmPlayer.Clock(){
            public long millis(){return 0;}public void pause()throws InterruptedException{throw new InterruptedException("PRIVATE_INTERRUPTION");}
        };
        StreamingPcmPlayer player=new StreamingPcmPlayer("r","a",7,(r,a,g)->true,sink,clock);
        try{player.play(new ByteArrayInputStream(StreamingPcmPlayerTest.wire(7,4,4,true)));fail();}
        catch(InterruptedException f){
            assertEquals("CANCELLED",f.getMessage());
            VoicePlaybackFailure safe=VoicePlaybackFailure.safe(f,VoicePlaybackFailure.Stage.NORMAL);
            assertEquals(VoicePlaybackFailure.Stage.SINK_HEAD,safe.stage);
            assertEquals(VoicePlaybackFailure.Category.CANCELLED,safe.category);
        }
        assertTrue(sink.closed);assertTrue(sink.aborted);
    }
    @Test public void observerFailureDoesNotAffectReceipt()throws Exception{
        StreamingPcmPlayerTest.Fake sink=new StreamingPcmPlayerTest.Fake();
        StreamingPcmPlayer player=sink.player();player.diagnostics(event->{throw new IllegalStateException("PRIVATE_LOGGER");});
        assertEquals(4,player.play(new ByteArrayInputStream(StreamingPcmPlayerTest.wire(7,4,4,true))).frameCount);
    }
}
