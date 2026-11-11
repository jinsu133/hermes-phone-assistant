package com.mist.connect

import android.content.Context
import com.k2fsa.sherpa.onnx.*

/** Local Hey Hermes KWS. Class name retained for existing capture/probe callers.
 * No microphone or network ownership here. Native resources are single-thread owned.
 */
internal class KoreanWakeDetector(
    private val context: Context,
    private val active: () -> Boolean,
    private val threshold: Float = 0.10f,
    private val score: Float = 1.5f,
    private val preview: (String, Boolean) -> Unit
) : AutoCloseable {
    companion object {
        // User-approved pronunciation tolerance; keep full HEY + name-like sequence.
        // Tuned on retained speech, not a general false-positive guarantee.
        private const val EXTRA_KEYWORDS = "▁HE Y ▁HAD ▁A ▁ME S S @HEY_HERMES\n▁HE Y ▁HE AD ▁OF ▁ME S S @HEY_HERMES\n▁HE Y ▁HE AD ▁OF ▁US @HEY_HERMES\n▁HE Y ▁HAD ▁A M ▁AS @HEY_HERMES"
    }
    private val spotter: KeywordSpotter
    private var stream: OnlineStream
    private var stopDetector: KoreanStopDetector? = null
    private var samples = 0L
    var wakeEndSample = 0L
        private set
    var stopped = false
        private set
    val endpointEvidence: SpeechEndGate.Evidence
        get() = stopDetector?.endpointEvidence ?: SpeechEndGate.Evidence.NONE

    init {
        check(active()) { "Cancelled" }
        val dir = "sherpa-wake"
        spotter = KeywordSpotter(context.assets, KeywordSpotterConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$dir/tokens.txt", modelType = "zipformer2",
                numThreads = 1, provider = "cpu", debug = false
            ),
            keywordsFile = "$dir/keywords.txt",
            keywordsScore = score, keywordsThreshold = threshold,
            numTrailingBlanks = 2
        ))
        try { stream = spotter.createStream(EXTRA_KEYWORDS) }
        catch (e: Throwable) { spotter.release(); throw e }
    }

    fun accept(pcm: ShortArray, count: Int): Boolean {
        require(count in 0..pcm.size)
        check(active()) { "Cancelled" }
        val input = FloatArray(count) { pcm[it] / 32768f }
        try { stream.acceptWaveform(input, 16000) } finally { input.fill(0f) }
        samples += count
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword == "HEY_HERMES") {
                // KWS token timestamps denote token positions, not a verified word end.
                // Fail closed: do not upload any pre-detection ambient audio.
                wakeEndSample = samples
                preview("Hey Hermes", true)
                return true
            }
        }
        return false
    }

    fun prepareStop() {
        if (stopDetector == null) stopDetector = KoreanStopDetector(context, active) { _, _ -> }
    }

    fun acceptStop(pcm: ShortArray, count: Int): Boolean {
        prepareStop()
        stopped = stopDetector!!.acceptStop(pcm, count)
        return stopped
    }

    fun reset() {
        stream.release()
        stream = spotter.createStream(EXTRA_KEYWORDS)
        stopDetector?.reset()
        samples = 0L; wakeEndSample = 0L; stopped = false
    }

    override fun close() {
        try { stopDetector?.close(); stopDetector = null }
        finally { try { stream.release() } finally { spotter.release() } }
    }
}
