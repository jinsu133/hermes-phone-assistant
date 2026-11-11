package com.mist.connect

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic silent WAV only: no microphone, provider, real approval or user session. */
class VoiceSegmentPlaybackTest {
    private fun response(data: ByteArray, index: String?, count: String?): HttpURLConnection =
        object: HttpURLConnection(URL("http://127.0.0.1/fixture")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(data)
            override fun getHeaderField(name: String?): String? = when(name) {
                "X-Audio-Format" -> ".wav"
                "X-Audio-Segment" -> index
                "X-Audio-Segments" -> count
                else -> null
            }
        }

    @Test fun malformedOrWrongSegmentHeadersAreRejected() {
        val wav = VoiceBridgeProtocol.toWav(ByteArray(3200))
        for ((index,count) in listOf("1" to "2", "0" to "0", "0" to "17", "zero" to "2", "0" to null)) {
            assertThrows(VoiceBridgeClient.BridgeException::class.java) {
                VoiceBridgeClient.readTts(response(wav,index,count),0)
            }
        }
    }

    @Test fun legacySinglePartHeadersRemainSupported() {
        val wav = VoiceBridgeProtocol.toWav(ByteArray(3200))
        val result = VoiceBridgeClient.readTts(response(wav,null,null),0)
        assertEquals(0,result.segment); assertEquals(1,result.segmentCount)
        assertArrayEquals(wav,result.bytes)
        assertThrows(VoiceBridgeClient.BridgeException::class.java) {
            VoiceBridgeClient.readTts(response(wav,null,null),1)
        }
    }

    @Test fun twoSilentSegmentsDecodeAndCompleteInOrder() {
        val sequence = ReplyAudioSequence()
        repeat(2) { index ->
            val wav = VoiceBridgeProtocol.toWav(ByteArray(3200 + index*1600))
            val result = VoiceBridgeClient.readTts(response(wav,index.toString(),"2"),index)
            val bytes = result.bytes!!
            sequence.begin(result.segment,result.segmentCount,bytes.size)
            assertFalse(sequence.complete())
            val done = CountDownLatch(1)
            val success = AtomicBoolean(false)
            var player: MediaPlayer? = null
            val source = VoiceRequestService.MemoryAudioSource(bytes)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player = MediaPlayer().apply {
                    setVolume(0f,0f)
                    setDataSource(source)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { success.set(true);done.countDown() }
                    setOnErrorListener { _,_,_ -> done.countDown();true }
                    prepareAsync()
                }
            }
            try {
                assertTrue(done.await(10,TimeUnit.SECONDS))
                assertTrue(success.get())
                sequence.played()
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.release(); source.close() }
                bytes.fill(0)
            }
            assertEquals(index == 1,sequence.complete())
        }
    }
}
