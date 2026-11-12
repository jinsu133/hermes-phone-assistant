package com.mist.connect

/** Presentation only: a five-row atlas with thirty authored raster cells per row. */
internal object MascotPlayback {
    const val FRAMES = 30
    const val ROWS = 5
    const val CELL = 96
    fun row(phase: UiPhase): Int = when (phase) {
        UiPhase.CAPTURING -> 1
        UiPhase.SUBMITTING, UiPhase.WAITING_REPLY, UiPhase.PREPARING -> 2
        UiPhase.PLAYING -> 3
        UiPhase.FOLLOW_UP -> 4
        else -> 0
    }
    fun frameMillis(phase: UiPhase): Long = when (phase) {
        UiPhase.WAKE_WAIT -> 84L
        UiPhase.CAPTURING, UiPhase.SUBMITTING, UiPhase.WAITING_REPLY,
        UiPhase.PREPARING, UiPhase.PLAYING, UiPhase.FOLLOW_UP -> 42L
        else -> 300L
    }
    /** The idle row plays once and rests on its last cell; active rows loop. */
    fun frame(phase: UiPhase, elapsed: Long): Int {
        val ticks = elapsed.coerceAtLeast(0L) / frameMillis(phase)
        return if (phase == UiPhase.WAKE_WAIT) minOf(ticks, (FRAMES - 1).toLong()).toInt()
        else (ticks % FRAMES).toInt()
    }
}

/** Fixed card width: reserve marginPx on BOTH sides, independent of phase or text. */
internal object OverlaySizing {
    fun width(screenPx: Int, maxPx: Int, marginPx: Int): Int {
        val available = (screenPx.toLong() - 2L * marginPx.coerceAtLeast(0)).coerceAtLeast(1L)
        return minOf(available, maxPx.coerceAtLeast(1).toLong()).toInt()
    }
}

/** One distinct glow per state: colour and rotation speed both change. */
internal object OverlayGlow {
    fun color(phase: UiPhase): Int = when (phase) {
        UiPhase.CAPTURING -> 0xFF1FA89A.toInt()          // listening: teal
        UiPhase.SUBMITTING, UiPhase.WAITING_REPLY,
        UiPhase.PREPARING -> 0xFF8A5CD6.toInt()          // thinking: violet
        UiPhase.PLAYING -> 0xFFEF8A30.toInt()            // speaking: amber
        UiPhase.FOLLOW_UP -> 0xFF3F95E0.toInt()          // follow-up: sky blue
        else -> 0xFFC4A062.toInt()                       // waiting for wake word: soft gold
    }
    /** Milliseconds per full turn of the glow. */
    fun periodMs(phase: UiPhase): Long = when (phase) {
        UiPhase.CAPTURING -> 1400L
        UiPhase.SUBMITTING, UiPhase.WAITING_REPLY, UiPhase.PREPARING -> 900L
        UiPhase.PLAYING -> 1800L
        UiPhase.FOLLOW_UP -> 2600L
        else -> 3600L
    }
}

/** Where the answer text sits for a given played fraction of the reply audio.
 * No word-level timing exists, so the spoken position is estimated as proportional to the
 * text height; the line being spoken is kept one line below the top of the viewport. */
internal object OverlayScroll {
    fun offset(fraction: Float, contentPx: Int, viewportPx: Int, linePx: Int): Int {
        val max = (contentPx - viewportPx).coerceAtLeast(0)
        if (max == 0 || fraction <= 0f) return 0
        val spoken = (fraction.coerceAtMost(1f) * contentPx).toInt()
        return (spoken - linePx).coerceIn(0, max)
    }
}
