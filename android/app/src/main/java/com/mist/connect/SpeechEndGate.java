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
    public static final double ENERGY_RMS = 450.0;
    /** A quiet gap this long before loud audio marks a new onset (a resumed utterance). */
    public static final long ONSET_GAP_MS = 300;
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
    private long samples, energySamples, lastEnergySample, lastProgressSample, onsetSample = -1;
    private long lastWordEnd = -1;
    private String lastText = "";
    private boolean recognized, endpointPending;
    private Decision decision = Decision.CONTINUE;
    // Diagnostic loudness figures only (block RMS); they never influence the decision.
    private double recentRms, floorRms = -1, peakRms, tailSumSq;
    private long tailSamples, firstProgressSample = -1, progressCount;

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
        boolean energy = (double) sum / count > ENERGY_RMS * ENERGY_RMS;
        if (energy) {
            long blockStart = samples - count;
            // Loud audio after a quiet gap is a new onset: the recognizer gets a fresh
            // stale window for it. Continuous loudness keeps its original onset.
            if (energySamples == 0 || blockStart - lastEnergySample >= ONSET_GAP_MS * RATE / 1000)
                onsetSample = blockStart;
            energySamples += count; lastEnergySample = samples;
        }
        double rms = Math.sqrt((double) sum / count);
        recentRms = recentRms == 0 ? rms : recentRms * 0.8 + rms * 0.2;
        peakRms = Math.max(peakRms, rms);
        if (samples > RATE * 3 / 10) floorRms = floorRms < 0 ? recentRms : Math.min(floorRms, recentRms);
        tailSumSq += (double) sum; tailSamples += count;

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
            if (firstProgressSample < 0) firstProgressSample = samples;
            progressCount++;
            tailSumSq = 0; tailSamples = 0; // Loudness after the last word starts here.
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
            // The recognizer reports words seconds late, and the wake word's own tail can
            // yield an early word. A pause followed by new speech must not be rejected on
            // that old word's clock. Count loud audio only, from the later of last word and
            // loud onset: a quiet tail is the endpoint, not missed speech.
            long loudStale = Math.max(0, lastEnergySample - Math.max(lastProgressSample, onsetSample)) * 1000 / RATE;
            if (quiet >= hangoverMs() && stale >= hangoverMs()) decision = Decision.COMPLETE;
            // Loud stale hypotheses may be noise OR missed continuing speech. Bounded
            // rejection is safer than treating either RMS or a decoder final as proof.
            else if (quiet < hangoverMs() && loudStale >= MAX_STALE_EVIDENCE_MS) decision = Decision.AMBIGUOUS;
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

    /** Numbers and flags only: no audio, transcript or token text. For logcat diagnosis. */
    public String metrics() {
        long ms = samples * 1000 / RATE;
        return "elapsed_ms=" + ms
            + " voiced_ms=" + voicedMs()
            + " recognized=" + recognized
            + " progress=" + progressCount
            + " first_word_ms=" + (firstProgressSample < 0 ? -1 : firstProgressSample * 1000 / RATE)
            + " quiet_ms=" + (energySamples == 0 ? -1 : (samples - lastEnergySample) * 1000 / RATE)
            + " stale_ms=" + (recognized ? (samples - lastProgressSample) * 1000 / RATE : -1)
            + " onset_ms=" + (onsetSample < 0 ? -1 : onsetSample * 1000 / RATE)
            + " endpoint_pending=" + endpointPending
            + " rms_threshold=" + Math.round(ENERGY_RMS)
            + " rms_recent=" + Math.round(recentRms)
            + " rms_floor=" + Math.round(Math.max(floorRms, 0))
            + " rms_peak=" + Math.round(peakRms)
            + " rms_after_last_word=" + (recognized && tailSamples > 0 ? Math.round(Math.sqrt(tailSumSq / tailSamples)) : -1);
    }

    /** Must be checked before exposing any collected PCM. */
    public void requireCompleteOrEmpty() {
        if (decision == Decision.SAFETY_LIMIT || decision == Decision.AMBIGUOUS)
            throw new Rejected(decision);
        if (decision != Decision.COMPLETE && decision != Decision.NO_SPEECH)
            throw new IllegalStateException("endpoint_not_finished");
    }
}
