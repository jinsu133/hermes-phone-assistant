package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class HermesWakePhraseGateTest {
    @Test public void selectedPhraseAndEnglishSpelling() {
        WakePhraseGate gate = new WakePhraseGate();
        assertEquals("헤르메스", WakePhraseGate.PHRASE);
        assertTrue(gate.accept("헤르메스", true));
        assertTrue(gate.accept("헤르메스 설정 열어줘", true));
        assertTrue(gate.accept("헤르메스, 설정 열어줘", true));
        assertTrue(gate.accept("hermes", true));
        assertTrue(gate.accept("Hermes open settings", true));
    }
    @Test public void rejectsOldNameAndEmbeddedMentions() {
        WakePhraseGate gate = new WakePhraseGate();
        assertFalse(gate.accept("춘식아", true));
        assertFalse(gate.accept("헤르만", true));
        assertFalse(gate.accept("헤르메스라고 불러", true));
        assertFalse(gate.accept("이름은 헤르메스", true));
        assertFalse(gate.accept("hermesian", true));
    }
    @Test public void stablePartialsFireOnlyOnce() {
        WakePhraseGate gate = new WakePhraseGate();
        assertFalse(gate.accept("헤르메스", false));
        assertTrue(gate.accept("헤르메스 설정", false));
        assertFalse(gate.accept("헤르메스 설정", true));
        assertTrue(gate.accept("헤르메스", true));
        gate.accept("헤르메스", false);
        gate.reset();
        assertFalse(gate.accept("헤르메스", false));
    }
}
