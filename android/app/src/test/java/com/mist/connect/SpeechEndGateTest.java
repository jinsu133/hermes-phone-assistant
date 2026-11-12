package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic policy fixtures, not acoustic/model accuracy tests. */
public class SpeechEndGateTest {
    private static short[] tone(int count) {
        short[] b = new short[count];
        for (int i=0;i<count;i++) b[i]=(short)(i%32<16 ? 1000 : -1000);
        return b;
    }
    private static SpeechEndGate.Evidence words(String text, boolean endpoint, long end) {
        return new SpeechEndGate.Evidence(text, endpoint, end);
    }
    private static SpeechEndGate spoken() {
        SpeechEndGate g=new SpeechEndGate();
        assertFalse(g.accept(tone(1600),1600,words("명령",false,1600)));
        assertFalse(g.accept(tone(1600),1600,words("명령 계속",false,3200)));
        return g;
    }
    @Test public void silenceDoesNotBecomeSpeech() {
        SpeechEndGate g=new SpeechEndGate(); short[] b=new short[1600];
        for(int i=0;i<69;i++)assertFalse(g.accept(b,b.length));
        assertTrue(g.accept(b,b.length)); assertFalse(g.hasSpeech());
        assertEquals(SpeechEndGate.Decision.NO_SPEECH,g.decision());
    }
    @Test public void speechEndsAfterSilence() {
        SpeechEndGate g=spoken(); short[] b=new short[1600];
        assertTrue(g.hasSpeech());
        for(int i=0;i<19;i++)assertFalse(g.accept(b,b.length));
        assertTrue(g.accept(b,b.length));
        g.requireCompleteOrEmpty();
        assertEquals(SpeechEndGate.Decision.COMPLETE,g.decision());
    }
    @Test public void energyAloneIsNeverSpeech() {
        SpeechEndGate g=new SpeechEndGate();
        for(int i=0;i<70;i++)g.accept(tone(1600),1600);
        assertFalse(g.hasSpeech());
        assertEquals(SpeechEndGate.Decision.NO_SPEECH,g.decision());
    }
    @Test public void silentHallucinationsAreNotSpeech() {
        SpeechEndGate g=new SpeechEndGate();
        for(int i=0;i<70;i++)g.accept(new short[1600],1600,words("음성 "+i,false,i*1600));
        assertFalse(g.hasSpeech());
        assertEquals(SpeechEndGate.Decision.NO_SPEECH,g.decision());
    }
    @Test public void shortBlocksDoNotLoseElapsedTime() {
        SpeechEndGate g=spoken(); short[] b=new short[1];
        for(int i=0;i<31999;i++)assertFalse(g.accept(b,1));
        assertTrue(g.accept(b,1));
        assertEquals(SpeechEndGate.Decision.COMPLETE,g.decision());
    }
    @Test public void capWinsOverEndpointAtExactBoundary() {
        SpeechEndGate g=new SpeechEndGate();
        for(int i=0;i<299;i++)assertFalse(g.accept(tone(1600),1600,words("말 "+i,false,i*1600)));
        assertTrue(g.accept(tone(1600),1600,words("완료",true,480000)));
        assertEquals(SpeechEndGate.Decision.SAFETY_LIMIT,g.decision());
        SpeechEndGate.Rejected e=assertThrows(SpeechEndGate.Rejected.class,g::requireCompleteOrEmpty);
        assertEquals(SpeechEndGate.Decision.SAFETY_LIMIT,e.reason);
    }
    @Test public void staleLoudPcmRejectsRatherThanCuts() {
        SpeechEndGate g=spoken();
        for(int i=0;i<27;i++)assertFalse(g.accept(tone(1600),1600,words("명령 계속",false,3200)));
        assertTrue(g.accept(tone(1600),1600,words("명령 계속",true,3200)));
        assertEquals(SpeechEndGate.Decision.AMBIGUOUS,g.decision());
        assertThrows(SpeechEndGate.Rejected.class,g::requireCompleteOrEmpty);
    }
    @Test public void emptyCallbacksDoNotRefreshOrInventEvidence() {
        SpeechEndGate g=spoken();
        for(int i=0;i<100;i++)assertFalse(g.accept(new short[0],0,words("새로운 말 "+i,true,i*10000)));
        assertEquals(2000,g.hangoverMs());
        for(int i=0;i<20;i++)g.accept(new short[1600],1600,words("",true,-1));
        assertEquals(SpeechEndGate.Decision.COMPLETE,g.decision());
    }
    @Test public void resumptionClearsFinalHangover() {
        SpeechEndGate g=spoken();
        g.accept(new short[1600],1600,words("명령 계속",true,3200));
        assertEquals(2000,g.hangoverMs());
        g.accept(tone(1600),1600,words("명령 계속 다음",false,6400));
        assertEquals(2000,g.hangoverMs());
        for(int i=0;i<19;i++)assertFalse(g.accept(new short[1600],1600));
        assertTrue(g.accept(new short[1600],1600));
    }
    @Test public void terminalOutcomeCannotBeRevived() {
        SpeechEndGate g=spoken();
        for(int i=0;i<20;i++)g.accept(new short[1600],1600);
        assertTrue(g.accept(tone(1600),1600,words("늦음",false,32000)));
        assertEquals(SpeechEndGate.Decision.COMPLETE,g.decision());
    }
    @Test public void invalidInputDoesNotAdvanceClock() {
        SpeechEndGate g=new SpeechEndGate();
        assertThrows(IllegalArgumentException.class,()->g.accept(new short[1],2));
        assertThrows(IllegalArgumentException.class,()->g.accept(new short[1],-1));
        assertThrows(IllegalArgumentException.class,()->g.accept(null,0));
        assertEquals(SpeechEndGate.Decision.CONTINUE,g.decision());
    }
    @Test public void wallCapCannotReturnPartialAudio() {
        SpeechEndGate g=spoken(); g.checkWallTime(29999);
        assertThrows(SpeechEndGate.Rejected.class,()->g.checkWallTime(30000));
        assertEquals(SpeechEndGate.Decision.SAFETY_LIMIT,g.decision());
    }
}
