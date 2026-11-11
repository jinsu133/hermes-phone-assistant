package com.mist.connect

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Feeds only bundled synthetic PCM; never calls AudioRecord or requests live speech. */
class AndroidNativeSttDeviceTest {
    @Test fun invalidAndCancelledInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("stt_pcm_invalid", AndroidNativeStt.transcribe(context, ByteArray(3), AndroidSttRequest(3)).getString("error"))
        assertEquals("stt_cancelled", AndroidNativeStt.transcribe(context, ByteArray(3200), AndroidSttRequest(3200).also { it.cancel() }).getString("error"))
    }

    @Test fun suppliedSyntheticPcmWithoutMicrophone() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val source = inst.context.assets.open("phone-stop.pcm").use { it.readBytes() }
        val pcm = source.copyOf(source.size + 32000)
        source.fill(0)
        try {
            val result = AndroidNativeStt.transcribe(inst.targetContext, pcm, AndroidSttRequest(pcm.size))
            assertTrue("SYNTHETIC_RECOGNITION_FAILED", result.getBoolean("success"))
            assertTrue(result.getBoolean("on_device"))
            val text = result.getString("transcript").trim()
            assertTrue("SYNTHETIC_STOP_NOT_RECOGNIZED", text.contains("스탑") || text.contains("스톱") || text.contains("stop", true))
        } finally { pcm.fill(0) }
    }

    @Test fun zeroPcmHasNoExecutableTranscript() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val result = AndroidNativeStt.transcribe(inst.targetContext, ByteArray(64000), AndroidSttRequest(64000))
        // Zero PCM is controlled input, not authority to classify arbitrary user audio as silence.
        assertFalse("ZERO_PCM_UNEXPECTED_SUCCESS", result.getBoolean("success"))
        assertEquals("", result.getString("transcript"))
        assertEquals(2, result.getInt("stt_contract"))
        assertTrue("ZERO_PCM_UNEXPECTED_REASON", result.getString("stt_reason") in
            setOf("no_match", "speech_timeout", "empty_final", "incomplete"))
        assertTrue("CLEANUP_UNCONFIRMED", result.getBoolean("stt_cleanup_confirmed"))
    }
}
