package com.mist.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectedMicTest {
    @Test fun releaseIsInert() {
        assertFalse(InjectedMic.offer(byteArrayOf(1, 0, 2, 0), debug = false))
        val block = shortArrayOf(7, 7)
        InjectedMic.overlay(block, 0, 2, debug = false)
        assertEquals(7, block[0].toInt())
    }

    @Test fun replacesSamplesThenSilenceThenLiveMic() {
        assertTrue(InjectedMic.offer(byteArrayOf(1, 0, 0xFF.toByte(), 0xFF.toByte()), debug = true))
        val block = ShortArray(4) { 9 }
        InjectedMic.overlay(block, 1, 3, debug = true)
        assertEquals(9, block[0].toInt())
        assertEquals(1, block[1].toInt())
        assertEquals(-1, block[2].toInt())
        assertEquals(0, block[3].toInt()) // trailing silence
        var left = InjectedMic.TAIL_SAMPLES - 1
        val big = ShortArray(4096)
        while (left > 0) { val n = minOf(left, big.size); InjectedMic.overlay(big, 0, n, debug = true); left -= n }
        assertFalse(InjectedMic.pending())
        val live = shortArrayOf(5)
        InjectedMic.overlay(live, 0, 1, debug = true)
        assertEquals(5, live[0].toInt())
    }

    @Test fun rejectsOddOrOversized() {
        assertFalse(InjectedMic.offer(ByteArray(3), debug = true))
        assertFalse(InjectedMic.offer(ByteArray(InjectedUtterance.MAX_BYTES + 2), debug = true))
    }
}
