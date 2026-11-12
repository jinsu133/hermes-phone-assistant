package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

class MascotPlaybackTest {
    @Test fun everyPhaseHasAValidThirtyFrameRow() {
        for (phase in UiPhase.entries) {
            val row = MascotPlayback.row(phase)
            assertTrue(row in 0..4)
            for (t in 0L..6000L step 37L) assertTrue(MascotPlayback.frame(phase, t) in 0..29)
        }
    }
    @Test fun listeningUsesOriginalTwentyFourFpsTiming() {
        assertEquals(1, MascotPlayback.row(UiPhase.CAPTURING))
        assertEquals(42L, MascotPlayback.frameMillis(UiPhase.CAPTURING))
    }
    @Test fun idleRowRestsOnItsLastCellWhileActiveRowsLoop() {
        assertEquals(0, MascotPlayback.frame(UiPhase.PLAYING, -5))
        assertEquals(1, MascotPlayback.frame(UiPhase.PLAYING, 42L))
        assertEquals(0, MascotPlayback.frame(UiPhase.PLAYING, 42L * 30))
        assertEquals(29, MascotPlayback.frame(UiPhase.WAKE_WAIT, 84L * 100))
    }
    @Test fun widthIsFixedAndBoundedOnBothSides() {
        assertEquals(320, OverlaySizing.width(800, 320, 16))
        assertEquals(168, OverlaySizing.width(200, 320, 16))
        assertEquals(1, OverlaySizing.width(10, 320, 16))
    }
    @Test fun everyVisibleStateHasItsOwnGlow() {
        val shown = listOf(UiPhase.WAKE_WAIT, UiPhase.CAPTURING, UiPhase.WAITING_REPLY,
            UiPhase.PLAYING, UiPhase.FOLLOW_UP)
        assertEquals(5, shown.map(OverlayGlow::color).toSet().size)
        assertEquals(5, shown.map(OverlayGlow::periodMs).toSet().size)
        assertEquals(OverlayGlow.color(UiPhase.SUBMITTING), OverlayGlow.color(UiPhase.WAITING_REPLY))
    }
    @Test fun scrollFollowsPlayedFractionWithinBounds() {
        assertEquals(0, OverlayScroll.offset(0.5f, 60, 60, 20))      // fits: never scrolls
        assertEquals(0, OverlayScroll.offset(0f, 300, 60, 20))
        assertEquals(0, OverlayScroll.offset(-1f, 300, 60, 20))
        assertEquals(130, OverlayScroll.offset(0.5f, 300, 60, 20))
        assertEquals(240, OverlayScroll.offset(1f, 300, 60, 20))     // ends on the last lines
        assertTrue(OverlayScroll.offset(0.3f, 300, 60, 20) < OverlayScroll.offset(0.6f, 300, 60, 20))
    }
    @Test fun progressIsOnlyReadFromTheCurrentPlayer() {
        val first = object : PlaybackProgress.Source {
            override fun positionMs() = 500L
            override fun durationMs() = 1000L
        }
        val second = object : PlaybackProgress.Source {
            override fun positionMs() = 250L
            override fun durationMs() = 1000L
        }
        assertEquals(-1f, PlaybackProgress.fraction(), 0f)
        PlaybackProgress.begin(first)
        assertEquals(0.5f, PlaybackProgress.fraction(), 0.001f)
        PlaybackProgress.begin(second)
        PlaybackProgress.end(first)                                    // replaced: no effect
        assertEquals(0.25f, PlaybackProgress.fraction(), 0.001f)
        PlaybackProgress.end(second)
        assertEquals(-1f, PlaybackProgress.fraction(), 0f)
    }
}
