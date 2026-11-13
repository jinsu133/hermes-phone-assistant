package com.mist.connect

import android.content.Context
import com.k2fsa.sherpa.onnx.*

/** Local wake/stop KWS using the user's phrases, sensitivity and learned pronunciations.
 * Class name retained for existing capture/probe callers.
 * No microphone or network ownership here. Native resources are single-thread owned.
 * Passing [threshold]/[score] pins the shipped "Hey Hermes" tuning for diagnostics.
 */
internal class KoreanWakeDetector(
    private val context: Context,
    private val active: () -> Boolean,
    threshold: Float? = null,
    score: Float? = null,
    private val preview: (String, Boolean) -> Unit
) : AutoCloseable {
    companion object {
        // User-approved pronunciation tolerance; keep full HEY + name-like sequence.
        // Tuned on retained speech, not a general false-positive guarantee.
        private val LEGACY_KEYWORDS = WakeSettings.DEFAULT_WAKE_TOKENS.drop(1)
            .joinToString("\n") { "$it @${WakeSettings.LEGACY_WAKE_LABEL}" }
        private val BASE = WakeSettings.DEFAULT_WAKE_TOKENS[0]
    }
    private val legacyAccepted: Boolean
    private val spotter: KeywordSpotter
    private var stream: OnlineStream
    private val wakeKeywords: String
    private val wakeLabel: String
    private val wakeName: String
    private val stopKeywords: String?
    private var stopStream: OnlineStream? = null
    private val stopIsolation: IsolatedPhraseGate
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
        val pinned = threshold != null || score != null
        val sensitivity = WakeSettings.sensitivity(context)
        if (pinned) {
            wakeKeywords = LEGACY_KEYWORDS; wakeLabel = WakeSettings.LEGACY_WAKE_LABEL
            wakeName = WakeSettings.DEFAULT_WAKE; stopKeywords = null; legacyAccepted = true
        } else {
            val wake = WakeSettings.sequences(WakeSettings.Kind.WAKE, context)
            // A phrase with no usable pronunciation never leaves the user without a wake word.
            wakeName = if (wake.isEmpty()) WakeSettings.DEFAULT_WAKE else WakeSettings.wakePhrase(context)
            val lines = wake.ifEmpty { WakeSettings.DEFAULT_WAKE_TOKENS }
            // keywords.txt already carries the base "Hey Hermes" line; never add it twice.
            legacyAccepted = BASE in lines
            wakeKeywords = WakeSettings.keywordSpec(lines.filter { it != BASE }, WakeSettings.WAKE_LABEL, sensitivity)
            wakeLabel = WakeSettings.WAKE_LABEL
            stopKeywords = WakeSettings.sequences(WakeSettings.Kind.STOP, context).takeIf { it.isNotEmpty() }
                ?.let { WakeSettings.keywordSpec(it, WakeSettings.STOP_LABEL, sensitivity) }
        }
        stopIsolation = IsolatedPhraseGate(WakeSettings.maxStopMillis(WakeSettings.stopPhrase(context)))
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
            keywordsScore = score ?: WakeSettings.score(sensitivity),
            keywordsThreshold = threshold ?: WakeSettings.threshold(sensitivity),
            numTrailingBlanks = 2
        ))
        try { stream = spotter.createStream(wakeKeywords) }
        catch (e: Throwable) { spotter.release(); throw e }
    }

    /** True for this detector's own label; the model file's built-in keyword is otherwise ignored. */
    private fun isWake(keyword: String) = keyword == wakeLabel ||
        (keyword == WakeSettings.LEGACY_WAKE_LABEL && legacyAccepted)

    fun accept(pcm: ShortArray, count: Int): Boolean {
        require(count in 0..pcm.size)
        check(active()) { "Cancelled" }
        val input = FloatArray(count) { pcm[it] / 32768f }
        try { stream.acceptWaveform(input, 16000) } finally { input.fill(0f) }
        samples += count
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isEmpty()) continue
            if (isWake(keyword)) {
                // KWS token timestamps denote token positions, not a verified word end.
                // Fail closed: do not upload any pre-detection ambient audio.
                wakeEndSample = samples
                preview(wakeName, true)
                return true
            }
            spotter.reset(stream)
        }
        return false
    }

    fun prepareStop() {
        if (stopDetector == null) stopDetector = KoreanStopDetector(context, active) { _, _ -> }
        if (stopStream == null && stopKeywords != null) stopStream = spotter.createStream(stopKeywords)
    }

    fun acceptStop(pcm: ShortArray, count: Int): Boolean {
        prepareStop()
        var hit = stopDetector!!.acceptStop(pcm, count)
        var spotted = false
        stopStream?.let { s ->
            val input = FloatArray(count) { pcm[it] / 32768f }
            try { s.acceptWaveform(input, 16000) } finally { input.fill(0f) }
            while (spotter.isReady(s)) {
                spotter.decode(s)
                val keyword = spotter.getResult(s).keyword
                if (keyword.isEmpty()) continue
                if (keyword == WakeSettings.STOP_LABEL) spotted = true
                spotter.reset(s)
            }
        }
        if (stopIsolation.accept(pcm, count, spotted)) hit = true
        stopped = stopped || hit
        return stopped
    }

    fun reset() {
        stream.release()
        stream = spotter.createStream(wakeKeywords)
        stopStream?.release()
        stopStream = stopKeywords?.takeIf { stopDetector != null }?.let { spotter.createStream(it) }
        stopIsolation.reset()
        stopDetector?.reset()
        samples = 0L; wakeEndSample = 0L; stopped = false
    }

    override fun close() {
        try { stopDetector?.close(); stopDetector = null }
        finally {
            try { stopStream?.release(); stopStream = null; stream.release() } finally { spotter.release() }
        }
    }
}
