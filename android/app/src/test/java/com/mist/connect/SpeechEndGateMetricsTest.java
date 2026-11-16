package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;

/** The diagnostic metrics line: numbers only, and it never changes a decision. */
public class SpeechEndGateMetricsTest {
    private static short[] tone(int count, int amp) {
        short[] b = new short[count];
        for (int i = 0; i < count; i++) b[i] = (short) (i % 32 < 16 ? amp : -amp);
        return b;
    }
    private static SpeechEndGate.Evidence words(String text, long end) {
        return new SpeechEndGate.Evidence(text, false, end);
    }

    @Test public void ambiguousNoiseAfterSpeechShowsLoudTail() {
        SpeechEndGate g = new SpeechEndGate();
        g.accept(tone(1600, 1000), 1600, words("지금 몇 시야", 1600));
        g.accept(tone(1600, 1000), 1600, words("지금 몇 시야 알려", 3200));
        boolean done = false;
        // Steady noise above the threshold, no new words: the gate rejects after 2.8 s.
        for (int i = 0; i < 40 && !done; i++) done = g.accept(tone(1600, 600), 1600, words("지금 몇 시야 알려", 3200));
        assertEquals(SpeechEndGate.Decision.AMBIGUOUS, g.decision());
        String m = g.metrics();
        assertTrue(m, m.contains("recognized=true"));
        assertTrue(m, m.contains("progress=2"));
        assertTrue(m, m.contains("quiet_ms=0"));
        assertTrue(m, m.contains("stale_ms=2800"));
        assertTrue(m, m.contains("rms_after_last_word=600"));
        assertTrue(m, m.contains("rms_threshold=450"));
    }

    @Test public void quietSpeechWithoutWordsIsNoSpeechWithLowPeak() {
        SpeechEndGate g = new SpeechEndGate();
        boolean done = false;
        for (int i = 0; i < 80 && !done; i++) done = g.accept(tone(1600, 300), 1600);
        assertEquals(SpeechEndGate.Decision.NO_SPEECH, g.decision());
        String m = g.metrics();
        assertTrue(m, m.contains("recognized=false"));
        assertTrue(m, m.contains("voiced_ms=0"));
        assertTrue(m, m.contains("rms_peak=300"));
        assertTrue(m, m.contains("first_word_ms=-1"));
        assertTrue(m, m.contains("rms_after_last_word=-1"));
    }

    @Test public void metricsCarryNoTranscript() {
        SpeechEndGate g = new SpeechEndGate();
        g.accept(tone(1600, 1000), 1600, words("비밀 문장", 1600));
        String m = g.metrics();
        assertFalse(m.contains("비밀"));
        assertTrue(m, m.matches("[a-z_]+=[-0-9a-z]+( [a-z_]+=[-0-9a-z]+)*"));
    }

    @Test public void readingMetricsDoesNotChangeTheDecision() {
        SpeechEndGate a = new SpeechEndGate(), b = new SpeechEndGate();
        short[] quiet = new short[1600];
        for (SpeechEndGate g : new SpeechEndGate[]{a, b}) {
            g.accept(tone(1600, 1000), 1600, words("명령", 1600));
            g.accept(tone(1600, 1000), 1600, words("명령 계속", 3200));
        }
        for (int i = 0; i < 20; i++) { a.accept(quiet, 1600); a.metrics(); b.accept(quiet, 1600); }
        assertEquals(b.decision(), a.decision());
        assertEquals(SpeechEndGate.Decision.COMPLETE, a.decision());
    }
}
