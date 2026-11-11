package com.mist.connect

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** Explicit-method-only device trial. No recorder, preferences, provider or production token. */
class DeviceStreamingTrialTest {
    @Test fun syntheticSpeakerService() = runTrial("synthetic")
    // Separate explicit method; host must already hold the ONE authorized provider response.
    @Test fun oneAuthorizedTtsSpeakerService() = runTrial("tts")

    private fun runTrial(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("explicit_device_streaming_trial", args.getString("trialConsent"))
        assertEquals(mode, args.getString("trialMode"))
        val bootstrapPort = args.getString("bootstrapPort")!!.toInt()
        require(bootstrapPort in 1024..65535)
        assertFalse(VoiceRequestService.handsfreeActive)
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val credential = random.joinToString("") { "%02x".format(it) }
        random.fill(0)
        val http = URL("http://127.0.0.1:$bootstrapPort/bootstrap").openConnection() as HttpURLConnection
        val port: Int
        try {
            http.connectTimeout = 3000; http.readTimeout = 3000
            http.requestMethod = "POST"; http.doOutput = true
            http.setRequestProperty("X-Trial-Mode", mode)
            http.setFixedLengthStreamingMode(credential.length)
            http.outputStream.use { it.write(credential.toByteArray(Charsets.US_ASCII)) }
            assertEquals(200, http.responseCode)
            port = http.inputStream.bufferedReader().use { it.readText().toInt() }
        } finally { http.disconnect() }
        val context = instrumentation.targetContext
        val sink = SpeakerSink(context.getSystemService(AudioManager::class.java))
        val intent = Intent(context, VoiceRequestService::class.java).setAction(VoiceRequestService.SYNTHETIC_TRIAL)
        try {
            instrumentation.runOnMainSync {
                VoiceRequestService.syntheticTrialResult = null
                VoiceRequestService.syntheticTrialFactory = { owner ->
                    SourceSyntheticTrial(true, port, credential, owner, SystemClock::elapsedRealtime, sink)
                }
                // Instrumentation-visible explicit service start, NOT a microphone FGS start.
                check(context.startService(intent) != null)
            }
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (VoiceRequestService.syntheticTrialResult == null && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(20)
            }
            val result = VoiceRequestService.syntheticTrialResult
            assertNotNull("service trial did not complete", result)
            assertTrue(result!!.frames >= 2400)
            assertEquals(1, result.syntheticEffects)
            assertTrue(result.replayDenied); assertTrue(result.offDenied)
            assertTrue(sink.closed); assertTrue(sink.started)
            assertEquals(result.frames, sink.finalHead)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink.routeType)
            assertNull(VoiceRequestService.syntheticTrialFactory)
            assertFalse(VoiceRequestService.handsfreeActive)
            android.util.Log.i("DeviceStreamingTrial", "stage=verified mode=$mode frames=${result.frames} correlation_id=${result.correlationId} speaker_route=true")
        } finally {
            instrumentation.runOnMainSync {
                VoiceRequestService.syntheticTrialFactory = null
                context.stopService(intent)
            }
            // Service owner owns close/join; never pretend cleanup passed by closing from test.
        }
    }

    private class SpeakerSink(private val manager: AudioManager): StreamingPcmPlayer.Sink {
        private var track: AudioTrack? = null
        @Volatile var started = false
        @Volatile var closed = false
        @Volatile var finalHead = 0L
        @Volatile var routeType = 0
        @Synchronized override fun start() {
            check(!closed && track == null)
            val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: error("built_in_speaker_unavailable")
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val current = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 8192)).build()
            track = current
            check(current.state == AudioTrack.STATE_INITIALIZED)
            check(current.setPreferredDevice(speaker))
            check(current.setVolume(0.15f) == AudioTrack.SUCCESS)
            // First positive write ACK releases the host's remaining PCM. Never wait for
            // head advancement before that ACK: an AudioTrack startup threshold can deadlock.
            if (android.os.Build.VERSION.SDK_INT >= 31) current.setStartThresholdInFrames(1)
            current.play(); started = true
        }
        @Synchronized override fun write(pcm: ByteArray, offset: Int, length: Int): Int =
            if (closed) -1 else track!!.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        @Synchronized override fun playedFrames(): Long {
            val current = track ?: return finalHead
            finalHead = current.playbackHeadPosition.toLong() and 0xffffffffL
            current.routedDevice?.let { routeType = it.type }
            return finalHead
        }
        @Synchronized override fun abort() { track?.pause(); track?.flush() }
        @Synchronized override fun close() {
            if (closed) return
            val current = track
            try { playedFrames(); current?.stop() } finally {
                try { current?.release() } finally { track = null; closed = true }
            }
        }
    }
}
