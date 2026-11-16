package com.mist.connect

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Why one hands-free capture ended. Metadata only: no audio, transcript or token text. */
internal enum class CaptureEnd(val code: String, val afterWakeWithoutUpload: Boolean) {
    COMPLETE("complete", false),
    INJECTED("injected", false),
    STOP_WORD("stop_word", false),
    NO_SPEECH("no_speech", true),
    AMBIGUOUS("endpoint_ambiguous", true),
    SAFETY_LIMIT("safety_limit", true),
    MIC_READ_FAILED("mic_read_failed", true),
    ERROR("error", true),
    /** Listening was stopped or handed over after the wake word, before an end decision. */
    INACTIVE_AFTER_WAKE("inactive_after_wake", true),
    /** Listening was stopped while still waiting for the wake word (normal OFF). */
    INACTIVE_WAITING("inactive_waiting", false),
    START_REFUSED("start_refused", false),
    /** The 30 s follow-up window after a reply closed without speech: normal, not a failure. */
    FOLLOWUP_QUIET("followup_quiet", false);

    companion object {
        fun of(error: Throwable): CaptureEnd = when {
            error is SpeechEndGate.Rejected ->
                if (error.reason == SpeechEndGate.Decision.SAFETY_LIMIT) SAFETY_LIMIT else AMBIGUOUS
            error is IllegalStateException && error.message == "mic_read_failed" -> MIC_READ_FAILED
            else -> ERROR
        }
        fun inactive(afterWake: Boolean) = if (afterWake) INACTIVE_AFTER_WAKE else INACTIVE_WAITING
        /** Follow-up listening has no wake word; quiet or OFF there is the normal way out. */
        fun settle(reason: CaptureEnd?, followup: Boolean, triggered: Boolean): CaptureEnd = when {
            followup && (reason == null || reason == NO_SPEECH) -> FOLLOWUP_QUIET
            reason != null -> reason
            else -> inactive(triggered)
        }
    }
}

/** Why one voice-learning round did not produce a learned line. Counts only. */
internal enum class TrainEnd(val code: String) {
    LEARNED("learned"),
    NO_VOICE("no_voice"),
    READ_SHORT("read_short"),
    CANCELLED("cancelled"),
    TOO_FEW_TOKENS("too_few_tokens"),
    TOO_MANY_TOKENS("too_many_tokens"),
    NO_WORD_START("no_word_start"),
}

/** Process-wide counters for capture and training end reasons. */
internal class EndCounters {
    private val capture = CaptureEnd.values().associateWith { AtomicLong() }
    private val train = TrainEnd.values().associateWith { AtomicLong() }
    private val lastCapture = AtomicReference<CaptureEnd?>()
    private val unshown = AtomicReference<CaptureEnd?>()
    private val lastTrain = AtomicReference<TrainEnd?>()

    fun captureEnded(end: CaptureEnd) {
        capture.getValue(end).incrementAndGet()
        lastCapture.set(end)
        // Only one pending notice: the newest abnormal after-wake end.
        if (end.afterWakeWithoutUpload) unshown.set(end) else if (end == CaptureEnd.COMPLETE ||
            end == CaptureEnd.INJECTED) unshown.set(null)
    }
    /** The newest after-wake end that was not uploaded and not yet shown, consumed once. */
    fun takeUnshown(): CaptureEnd? = unshown.getAndSet(null)
    fun trainEnded(end: TrainEnd) { train.getValue(end).incrementAndGet(); lastTrain.set(end) }

    fun captureCounts(): Map<String, Long> = capture.entries.associate { it.key.code to it.value.get() }
    fun trainCounts(): Map<String, Long> = train.entries.associate { it.key.code to it.value.get() }
    fun lastCapture(): CaptureEnd? = lastCapture.get()
    fun lastTrain(): TrainEnd? = lastTrain.get()
}
