package com.mist.connect;

/** Local endpoint policy, not a VAD or a proof of the speaker's intent.
 * Recognizer progress AND PCM support are required. Energy alone is never a command.
 * Continuous recognized speech has no short utterance deadline. The transport's 30s
 * safety bound rejects the WHOLE capture; it must never return a clipped command.
 */
public final class SpeechEndGate {
    public static final long MIN_HANGOVER_MS = 2000;
    public static final long MAX_HANGOVER_MS = 2000;
    public static final long MAX_UTTERANCE_MS = 30000;
    public static final long MAX_STALE_EVIDENCE_MS = 2800;
    private static final long RATE = 16000;

    public enum Decision { CONTINUE, COMPLETE, NO_SPEECH, AMBIGUOUS, SAFETY_LIMIT }

    /** Safe non-audio signal for the service; no partial buffer escapes on rejection. */
    public static final class Rejected extends IllegalStateException {
        public final Decision reason;
        public Rejected(Decision reason) {
            super(reason == Decision.SAFETY_LIMIT ? "endpoint_safety_limit" : "endpoint_ambiguous");
            if (reason != Decision.SAFETY_LIMIT && reason != Decision.AMBIGUOUS)
                throw new IllegalArgumentException("not_rejection");
            this.reason = reason;
        }
    }

    /** A snapshot, NOT an acceptWaveForm boolean masquerading as speech activity.
     * wordEndSample is the last meaningful Vosk word end, or -1 if unavailable.
     * Its clock is recognizer-owned and need not start at zero after reset().
     */
    public static final class Evidence {
        public static final Evidence NONE = new Evidence("", false, -1);
        final String text;
        final boolean endpoint;
        final long wordEndSample;
        public Evidence(String text, boolean endpoint, long wordEndSample) {
            this.text = text == null ? "" : text;
            this.endpoint = endpoint;
            this.wordEndSample = wordEndSample;
        }
    }

    private final long initialSilenceMs;
    private final long maxCaptureMs;
    private long samples, energySamples, lastEnergySample, lastProgressSample;
    private long lastWordEnd = -1;
    private String lastText = "";
    private boolean recognized, endpointPending;
    private Decision decision = Decision.CONTINUE;

    public SpeechEndGate() { this(7000); }
    public SpeechEndGate(long initialSilenceMs) {
        if (initialSilenceMs <= 0 || initialSilenceMs > MAX_UTTERANCE_MS)
            throw new IllegalArgumentException("initial_silence_bound");
        this.initialSilenceMs = initialSilenceMs;
        // A full 30-second follow-up wait must not consume its whole capture budget.
        this.maxCaptureMs = initialSilenceMs == MAX_UTTERANCE_MS
                ? MAX_UTTERANCE_MS * 2 : MAX_UTTERANCE_MS;
    }

    public long hangoverMs() { return endpointPending ? MIN_HANGOVER_MS : MAX_HANGOVER_MS; }
    public long voicedMs() { return energySamples * 1000 / RATE; }
    public boolean hasSpeech() { return recognized && energySamples >= RATE / 5; }
    public Decision decision() { return decision; }

    /** Legacy PCM-only callers may stop waiting, but cannot authorize speech upload. */
    public boolean accept(short[] pcm, int count) { return accept(pcm, count, Evidence.NONE); }

    public boolean accept(short[] pcm, int count, Evidence evidence) {
        if (pcm == null || count < 0 || count > pcm.length || evidence == null)
            throw new IllegalArgumentException("endpoint_input");
        if (decision != Decision.CONTINUE) return true;
        if (count == 0) return false; // A repeated callback is not new audio or evidence.
        samples += count; // Sample clock avoids per-block rounding losses.
        long sum = 0;
        for (int i = 0; i < count; i++) sum += (long) pcm[i] * pcm[i];
        boolean energy = (double) sum / count > 450.0 * 450.0;
        if (energy) { energySamples += count; lastEnergySample = samples; }

        // Empty, unknown-only and punctuation callbacks do not prolong capture.
        String text = evidence.text.length() <= 4096
            ? evidence.text.replaceAll("\\[[^\\]]*\\]|<[^>]*>", "").trim().replaceAll("\\s+", " ") : "";
        boolean meaningful = text.codePoints().anyMatch(Character::isLetterOrDigit);
        boolean progress = meaningful && (!text.equals(lastText)
            || evidence.wordEndSample > lastWordEnd);
        if (progress) {
            recognized = true;
            lastProgressSample = samples;
            endpointPending = false; // Resumption cancels the old pending endpoint.
        }
        if (meaningful) {
            lastText = text;
            lastWordEnd = Math.max(lastWordEnd, evidence.wordEndSample);
            if (evidence.endpoint) endpointPending = true;
        }

        // Cap wins even when silence/decoder endpoint happens on exactly this block.
        if (samples >= maxCaptureMs * RATE / 1000) decision = Decision.SAFETY_LIMIT;
        else if (!hasSpeech()) {
            if (samples >= initialSilenceMs * RATE / 1000) decision = Decision.NO_SPEECH;
        } else {
            long quiet = (samples - lastEnergySample) * 1000 / RATE;
            long stale = (samples - lastProgressSample) * 1000 / RATE;
            if (quiet >= hangoverMs() && stale >= hangoverMs()) decision = Decision.COMPLETE;
            // Loud stale hypotheses may be noise OR missed continuing speech. Bounded
            // rejection is safer than treating either RMS or a decoder final as proof.
            else if (stale >= MAX_STALE_EVIDENCE_MS) decision = Decision.AMBIGUOUS;
        }
        return decision != Decision.CONTINUE;
    }

    /** Called on the same capture thread, including zero-byte nonblocking reads.
     * Wall time protects storage/ownership when decoding or PCM delivery stalls.
     */
    public void checkWallTime(long elapsedMs) {
        if (elapsedMs >= maxCaptureMs) {
            decision = Decision.SAFETY_LIMIT;
            throw new Rejected(decision);
        }
    }

    /** Must be checked before exposing any collected PCM. */
    public void requireCompleteOrEmpty() {
        if (decision == Decision.SAFETY_LIMIT || decision == Decision.AMBIGUOUS)
            throw new Rejected(decision);
        if (decision != Decision.COMPLETE && decision != Decision.NO_SPEECH)
            throw new IllegalStateException("endpoint_not_finished");
    }
}
