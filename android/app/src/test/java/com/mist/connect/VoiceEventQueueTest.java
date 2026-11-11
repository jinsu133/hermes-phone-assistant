package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceEventQueueTest {
    @Test public void rejectsOtherRequestAndRepeatedEvents() {
        VoiceEventQueue q=new VoiceEventQueue("a");
        assertFalse(q.accept("b",1));assertTrue(q.accept("a",1));
        assertFalse(q.accept("a",1));assertFalse(q.accept("a",0));
        assertTrue(q.active("a",1));assertEquals(1,q.cursor());
    }
    @Test public void terminalDiscardsInFlightProgress() {
        VoiceEventQueue q=new VoiceEventQueue("a");
        q.accept("a",1);q.accept("a",2);assertFalse(q.active("a",1));
        q.finish();assertFalse(q.active("a",2));assertFalse(q.accept("a",3));
    }
}
