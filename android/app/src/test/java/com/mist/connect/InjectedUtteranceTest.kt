package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

class InjectedUtteranceTest {
    private fun wav(rate: Int = 16000, channels: Int = 1, bits: Int = 16, data: ByteArray = ByteArray(320) { 1 }): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun s(v: String) = out.write(v.toByteArray(Charsets.US_ASCII))
        fun i16(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
        fun i32(v: Int) { i16(v and 0xffff); i16((v ushr 16) and 0xffff) }
        s("RIFF"); i32(36 + data.size); s("WAVE"); s("fmt "); i32(16); i16(1); i16(channels); i32(rate)
        i32(rate * channels * bits / 8); i16(channels * bits / 8); i16(bits); s("data"); i32(data.size); out.write(data)
        return out.toByteArray()
    }

    @Test fun parsesOnlySixteenKilohertzMonoPcm() {
        assertEquals(320, InjectedUtterance.parseWav(wav())!!.size)
        assertNull(InjectedUtterance.parseWav(wav(rate = 44100)))
        assertNull(InjectedUtterance.parseWav(wav(channels = 2)))
        assertNull(InjectedUtterance.parseWav(wav(bits = 8)))
        assertNull(InjectedUtterance.parseWav(ByteArray(10)))
        val truncated = wav().copyOf(60)
        assertNull(InjectedUtterance.parseWav(truncated))
    }

    @Test fun releaseBuildNeverAcceptsOrYields() {
        assertFalse(InjectedUtterance.offer(ByteArray(320), debug = false))
        assertTrue(InjectedUtterance.offer(ByteArray(320), debug = true))
        assertNull(InjectedUtterance.take(debug = false))
        assertEquals(320, InjectedUtterance.take(debug = true)!!.size)
        assertNull(InjectedUtterance.take(debug = true)) // one-shot
    }

    @Test fun oversizedOrOddUtteranceIsRefused() {
        assertFalse(InjectedUtterance.offer(ByteArray(InjectedUtterance.MAX_BYTES + 2), debug = true))
        assertFalse(InjectedUtterance.offer(ByteArray(321), debug = true))
        assertNull(InjectedUtterance.take(debug = true))
    }
}
