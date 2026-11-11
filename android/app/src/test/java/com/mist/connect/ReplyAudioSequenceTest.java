package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReplyAudioSequenceTest {
    @Test public void allPartsMustActuallyFinishPlaying() {
        ReplyAudioSequence s = new ReplyAudioSequence();
        s.begin(0, 2, 10);
        assertFalse(s.complete());
        s.played();
        assertEquals(1, s.next());
        assertFalse(s.complete());
        s.begin(1, 2, 15);
        assertFalse(s.complete());
        s.played();
        assertTrue(s.complete());
    }
    @Test public void singlePartIsBackwardCompatible() {
        ReplyAudioSequence s = new ReplyAudioSequence();
        s.begin(0, 1, 12); s.played(); assertTrue(s.complete());
    }
    @Test public void cancellationNeverCompletesOrAdmitsAnotherPart() {
        ReplyAudioSequence s = new ReplyAudioSequence();
        s.begin(0, 2, 10); s.played(); s.cancel();
        assertFalse(s.complete());
        assertThrows(IllegalStateException.class, () -> s.begin(1, 2, 10));
    }
    @Test public void orderAndCountCannotChange() {
        ReplyAudioSequence s = new ReplyAudioSequence();
        assertThrows(IllegalStateException.class, () -> s.begin(1, 2, 10));
        s.begin(0, 2, 10); s.played();
        assertThrows(IllegalStateException.class, () -> s.begin(1, 3, 10));
        assertThrows(IllegalStateException.class, () -> s.begin(0, 2, 10));
    }
    @Test public void cannotSkipPlaybackOrPlayTwice() {
        ReplyAudioSequence s = new ReplyAudioSequence();
        assertThrows(IllegalStateException.class, s::played);
        s.begin(0, 2, 10);
        assertThrows(IllegalStateException.class, () -> s.begin(1, 2, 10));
        s.played();
        assertThrows(IllegalStateException.class, s::played);
    }
    @Test public void aggregateBudgetIncludesEveryPart() {
        ReplyAudioSequence s = new ReplyAudioSequence();
        s.begin(0, 2, VoiceBridgeProtocol.MAX_REPLY_AUDIO); s.played();
        assertThrows(IllegalStateException.class, () -> s.begin(1, 2, 1));
    }
    @Test public void rejectsInvalidBounds() {
        for (int total : new int[]{0, -1, 17, Integer.MAX_VALUE}) {
            ReplyAudioSequence s = new ReplyAudioSequence();
            assertThrows(IllegalStateException.class, () -> s.begin(0, total, 10));
        }
        ReplyAudioSequence s = new ReplyAudioSequence();
        assertThrows(IllegalStateException.class, () -> s.begin(0, 1, 0));
        assertThrows(IllegalStateException.class, () -> s.begin(0, 1, Integer.MAX_VALUE));
    }
}
