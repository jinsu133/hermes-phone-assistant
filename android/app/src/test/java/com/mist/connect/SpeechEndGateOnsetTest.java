package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;

/** A pause after an early word (e.g. the wake word's tail) then new speech: real-device v87 case. */
public class SpeechEndGateOnsetTest {
    private static short[] tone(int count, int amp) {
        short[] b = new short[count];
        for (int i = 0; i < count; i++) b[i] = (short) (i % 32 < 16 ? amp : -amp);
        return b;
    }
    private static final short[] QUIET = new short[1600]; // 100 ms
    private static SpeechEndGate.Evidence words(String text, long end) {
        return new SpeechEndGate.Evidence(text, false, end);
    }

    /** Early word at 0.2 s, 1.5 s pause, then 2.5 s of speech the recognizer reports late. */
    @Test public void pauseThenSpeechIsNotRejectedOnTheOldWordsClock() {
        SpeechEndGate g = new SpeechEndGate();
        g.accept(tone(1600, 1000), 1600);
        g.accept(tone(1600, 1000), 1600, words("스", 3200));
        for (int i = 0; i < 15; i++) assertFalse(g.accept(QUIET, 1600, words("스", 3200)));
        // 2.5 s of loud speech without new words: the old rule rejected at 2.8 s after "스".
        for (int i = 0; i < 25; i++) assertFalse("rejected mid-speech at block " + i,
                g.accept(tone(1600, 1200), 1600, words("스", 3200)));
        // The late final arrives during the following silence; the capture then completes.
        g.accept(QUIET, 1600, words("스 지금 몇 시야", 70000));
        boolean done = false;
        for (int i = 0; i < 40 && !done; i++) done = g.accept(QUIET, 1600, words("스 지금 몇 시야", 70000));
        assertTrue(done);
        assertEquals(SpeechEndGate.Decision.COMPLETE, g.decision());
    }

    /** Without any late word, the same pause-then-speech still completes after 2 s of quiet. */
    @Test public void pauseThenSpeechWithoutLateWordsCompletesOnQuiet() {
        SpeechEndGate g = new SpeechEndGate();
        g.accept(tone(1600, 1000), 1600);
        g.accept(tone(1600, 1000), 1600, words("스", 3200));
        for (int i = 0; i < 15; i++) g.accept(QUIET, 1600);
        for (int i = 0; i < 20; i++) assertFalse(g.accept(tone(1600, 1200), 1600));
        boolean done = false;
        for (int i = 0; i < 40 && !done; i++) done = g.accept(QUIET, 1600);
        assertEquals(SpeechEndGate.Decision.COMPLETE, g.decision());
    }

    /** A new onset only renews the window; loud audio with no words is still bounded at 2.8 s. */
    @Test public void newOnsetWithoutWordsIsStillBounded() {
        SpeechEndGate g = new SpeechEndGate();
        g.accept(tone(1600, 1000), 1600);
        g.accept(tone(1600, 1000), 1600, words("스", 3200));
        for (int i = 0; i < 10; i++) g.accept(QUIET, 1600);
        boolean done = false; int blocks = 0;
        while (!done && blocks < 100) { done = g.accept(tone(1600, 1200), 1600); blocks++; }
        assertEquals(SpeechEndGate.Decision.AMBIGUOUS, g.decision());
        assertEquals(28, blocks); // exactly 2.8 s after the new onset
        assertTrue(g.metrics(), g.metrics().contains("onset_ms=1200"));
    }

    /** Short gaps inside speech (< 300 ms) are not new onsets: continuous noise keeps its clock. */
    @Test public void shortGapsDoNotRenewTheWindow() {
        SpeechEndGate g = new SpeechEndGate();
        g.accept(tone(1600, 1000), 1600, words("명령", 1600));
        g.accept(tone(1600, 1000), 1600, words("명령 계속", 3200));
        boolean done = false; int blocks = 0;
        while (!done && blocks < 100) {
            done = g.accept(blocks % 3 == 2 ? QUIET : tone(1600, 1200), 1600, words("명령 계속", 3200));
            blocks++;
        }
        assertEquals(SpeechEndGate.Decision.AMBIGUOUS, g.decision());
        assertEquals(28, blocks);
    }
}
