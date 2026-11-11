package com.mist.connect

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** PREPARED ONLY. Explicitly selected one-shot service/HTTP probe; no mic, speaker or TTS.
 * Requires an already-running, paired candidate McpService. Does not start/stop it or edit prefs.
 * Credentials remain device-memory-only; never instrumentation arguments, status, logs or files.
 */
class G2BSttServiceDeviceTest {
    @Test fun selectedZeroPcmServiceHttpOnce() {
        check(InstrumentationRegistry.getArguments().getString("g2b_stt_trial") == "zero-pcm-once") {
            "EXPLICIT_G2B_TRIAL_REQUIRED"
        }
        val inst = InstrumentationRegistry.getInstrumentation()
        check(McpService.instance != null) { "CONTROL_SERVICE_NOT_RUNNING" }
        check(!VoiceRequestService.handsfreeActive) { "LISTENING_MUST_BE_OFF" }
        val prefs = inst.targetContext.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val token = prefs.getString("mcp_token", "") ?: ""
        val identity = prefs.getString("phone_instance_id", "") ?: ""
        check(token.length >= 32 && identity.isNotEmpty()) { "PAIRING_REQUIRED" }
        val pcm = ByteArray(64000)
        val c = URL("http://127.0.0.1:5000/stt/ko").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects=false;c.useCaches=false;c.connectTimeout=3000;c.readTimeout=35000
            c.requestMethod="POST";c.doOutput=true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("Connection", "close");c.setFixedLengthStreamingMode(pcm.size)
            c.outputStream.use { it.write(pcm) }
            assertEquals("HTTP_CONTRACT_FAILED",200,c.responseCode)
            val buffer=ByteArray(32769);var count=0
            c.inputStream.use { stream ->
                while(count<buffer.size) { val n=stream.read(buffer,count,buffer.size-count);if(n<0)break;count+=n }
            }
            check(count<buffer.size) { "RESPONSE_TOO_LARGE" }
            val r=JSONObject(String(buffer,0,count,Charsets.UTF_8));buffer.fill(0)
            assertEquals("IDENTITY_MISMATCH",identity,r.getString("instance_id"))
            assertEquals(2,r.getInt("stt_contract"))
            assertFalse("ZERO_PCM_UNEXPECTED_SUCCESS",r.getBoolean("success"))
            assertTrue("TRANSCRIPT_MUST_BE_EMPTY",r.getString("transcript").isEmpty())
            val reason=r.getString("stt_reason")
            assertTrue("UNEXPECTED_NATIVE_REASON",reason in setOf("no_match","speech_timeout","empty_final","incomplete"))
            assertTrue("CLEANUP_UNCONFIRMED",r.getBoolean("stt_cleanup_confirmed"))
            assertFalse("DIAGNOSTIC_OPT_OUT_FAILED",r.has("stt_diagnostics"))
            inst.sendStatus(0,Bundle().apply { putString("g2b_stt_reason",reason);putBoolean("cleanup_confirmed",true) })
        } finally { pcm.fill(0);c.disconnect() }
    }
}
