package com.mist.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SttPrecheckTest {
    private val pcm = ByteArray(640) { (it % 7).toByte() }
    @Before fun reset() = SttPrecheck.clear()

    @Test fun sameBytesReuseOnce() {
        SttPrecheck.rememberRaw(pcm, "r", 1000)
        assertEquals("r", SttPrecheck.takeRaw(pcm.copyOf(), 2000))
        assertNull(SttPrecheck.takeRaw(pcm, 2000))
    }

    @Test fun differentBytesAreNotReused() {
        SttPrecheck.rememberRaw(pcm, "r", 1000)
        val other = pcm.copyOf().also { it[10] = 99 }
        assertNull(SttPrecheck.takeRaw(other, 2000))
        assertNull(SttPrecheck.takeRaw(pcm, 2000)) // a miss also retires the entry
    }

    @Test fun staleResultIsNotReused() {
        SttPrecheck.rememberRaw(pcm, "r", 1000)
        assertNull(SttPrecheck.takeRaw(pcm, 1000 + 60_001))
    }
}
