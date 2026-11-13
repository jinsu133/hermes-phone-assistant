package com.mist.connect;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.Assert.*;

public class IsolatedPhraseGateTest {
    private static short[] tone(int ms, int amplitude) {
        short[] out = new short[ms * 16];
        for (int i = 0; i < out.length; i++) out[i] = (short) (amplitude * Math.sin(i * 0.2));
        return out;
    }

    /** Feeds audio in 100 ms chunks; the hit is reported on the chunk at hitMs. Returns confirmation. */
    private static boolean run(IsolatedPhraseGate gate, int hitMs, short[]... parts) {
        int total = 0;
        for (short[] p : parts) total += p.length;
        short[] all = new short[total];
        int at = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, all, at, p.length); at += p.length; }
        boolean confirmed = false;
        for (int off = 0; off < all.length; off += 1600) {
            int n = Math.min(1600, all.length - off);
            boolean hit = off / 16 <= hitMs && hitMs < (off + n) / 16;
            confirmed |= gate.accept(Arrays.copyOfRange(all, off, off + n), n, hit);
        }
        return confirmed;
    }

    @Test public void shortIsolatedWordConfirmsAfterQuiet() {
        assertTrue(run(new IsolatedPhraseGate(1500), 900, tone(500, 0), tone(500, 3000), tone(800, 0)));
    }

    @Test public void wordInsideLongSentenceIsRejected() {
        assertFalse(run(new IsolatedPhraseGate(1500), 1500, tone(500, 0), tone(3000, 3000), tone(800, 0)));
    }

    @Test public void noConfirmationWhileSpeechContinues() {
        assertFalse(run(new IsolatedPhraseGate(1500), 900, tone(500, 0), tone(500, 3000), tone(300, 0), tone(2000, 3000)));
    }

    @Test public void lateHitRightAfterUtteranceStillCounts() {
        assertTrue(run(new IsolatedPhraseGate(1500), 1700, tone(500, 0), tone(500, 3000), tone(1500, 0)));
    }

    @Test public void noHitNoStop() {
        assertFalse(run(new IsolatedPhraseGate(1500), -1, tone(500, 0), tone(500, 3000), tone(800, 0)));
    }

    @Test public void customStopTextMatchesExactly() {
        StopPhraseGate gate = new StopPhraseGate(new HashSet<>(Arrays.asList("그만", "Stop now")));
        assertTrue(gate.accept("그만.", true));
        assertTrue(gate.accept("stop  now", true));
        assertFalse(gate.accept("그만 해 줘", true));
        assertFalse(gate.accept("그만", false));
    }

    @Test public void sttLanguageFollowsAppLanguage() {
        assertEquals("en-US", SttLanguage.pick(Arrays.asList("ko-KR", "en-US"), "en-US"));
        assertEquals("es-US", SttLanguage.pick(Arrays.asList("ko-KR", "es-US"), "es-ES"));
        assertNull(SttLanguage.pick(Arrays.asList("ko-KR"), "th-TH"));
        assertEquals("ko-KR", SttLanguage.pick(Arrays.asList("ko-KR"), "ko-KR"));
    }
}
