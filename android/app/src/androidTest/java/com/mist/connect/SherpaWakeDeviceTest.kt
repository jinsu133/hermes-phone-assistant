package com.mist.connect

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Direct native loading/inference only. No recording, network, permissions or UI actions. */
@RunWith(AndroidJUnit4::class)
class SherpaWakeDeviceTest {
    @Test fun syntheticEnglishWake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("hey-hermes-synthetic.pcm").use { it.readBytes() }
        val pcm = ShortArray(bytes.size / 2) { ((bytes[it*2].toInt() and 255) or (bytes[it*2+1].toInt() shl 8)).toShort() }
        KoreanWakeDetector(instrumentation.targetContext, { true }) { _, _ -> }.use { detector ->
            var hit = false
            var pos = 0
            while (pos < pcm.size && !hit) {
                val count = minOf(1600, pcm.size-pos)
                hit = detector.accept(pcm.copyOfRange(pos, pos+count), count)
                pos += count
            }
            assertTrue("Synthetic English Hey Hermes must activate", hit)
            assertTrue(detector.wakeEndSample > 0)
            assertTrue(detector.wakeEndSample <= pcm.size)
        }
        bytes.fill(0); pcm.fill(0)
    }
    @Test fun nativeModelSilenceAndReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var previews = 0
        KoreanWakeDetector(context, { true }) { _, _ -> previews++ }.use { detector ->
            repeat(2) {
                repeat(30) { assertFalse(detector.accept(ShortArray(1600), 1600)) }
                assertEquals(0L, detector.wakeEndSample)
                assertFalse(detector.stopped)
                detector.reset()
            }
        }
        assertEquals(0, previews)
    }
}
