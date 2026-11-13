package com.mist.connect

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import kotlin.math.sqrt

/**
 * Learns how the user pronounces a wake/stop phrase: decodes a short recording with the
 * same on-device model the wake detector uses and keeps only the resulting token text.
 * Audio stays in memory for one decode and is cleared afterwards; nothing leaves the phone.
 */
internal class WakeTrainer(private val context: Context) : AutoCloseable {
    private val recognizer: OnlineRecognizer

    init {
        val dir = "sherpa-wake"
        recognizer = OnlineRecognizer(context.assets, OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$dir/tokens.txt", modelType = "zipformer2",
                numThreads = 1, provider = "cpu", debug = false
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search"
        ))
    }

    /**
     * Records one utterance: waits up to 5 s for speech, then stops after 0.7 s of quiet
     * or 3 s of speech. Returns null when nothing was said. Caller owns the microphone lease.
     */
    @Suppress("MissingPermission")
    fun record(active: () -> Boolean, level: (Float) -> Unit): ShortArray? {
        val rate = 16000
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, rate))
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            val frame = ShortArray(1600)
            val kept = ArrayList<ShortArray>()
            var floor = 0.0
            var started = -1
            var quiet = 0
            var frames = 0
            while (active() && frames < 80) {
                var n = 0
                while (n < frame.size && active()) {
                    val r = audio.read(frame, n, frame.size - n)
                    if (r < 0) error("read $r")
                    n += r
                }
                if (n < frame.size) return null
                frames++
                var sum = 0.0
                for (s in frame) sum += s.toDouble() * s
                val rms = sqrt(sum / frame.size)
                level((rms / 6000.0).coerceIn(0.0, 1.0).toFloat())
                val speech = rms > maxOf(400.0, floor * 3.0)
                if (!speech && started < 0) floor = if (floor <= 0) rms else floor * 0.9 + rms * 0.1
                if (started < 0) {
                    kept.add(frame.copyOf()); if (kept.size > 3) kept.removeAt(0).fill(0)
                    if (speech) started = frames
                    else if (frames >= 50) return null
                } else {
                    kept.add(frame.copyOf())
                    quiet = if (speech) 0 else quiet + 1
                    if (quiet >= 7 || frames - started >= 30) break
                }
            }
            if (started < 0 || !active()) return null
            val out = ShortArray(kept.sumOf { it.size })
            var at = 0
            for (k in kept) { k.copyInto(out, at); at += k.size; k.fill(0) }
            frame.fill(0)
            return out
        } finally {
            try { audio.stop() } catch (_: Exception) {}
            audio.release()
            level(0f)
        }
    }

    /** Model tokens for one recording; the recording is wiped before returning. */
    fun decode(pcm: ShortArray): List<String> {
        val stream = recognizer.createStream("")
        try {
            val input = FloatArray(pcm.size) { pcm[it] / 32768f }
            stream.acceptWaveform(input, 16000)
            input.fill(0f)
            // Trailing quiet flushes the streaming encoder's lookahead.
            stream.acceptWaveform(FloatArray(8000), 16000)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).tokens.toList()
        } finally {
            pcm.fill(0)
            stream.release()
        }
    }

    override fun close() { recognizer.release() }
}
