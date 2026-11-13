package com.mist.connect;

/**
 * Confirms a spotted stop word only when it was spoken on its own: a short burst of speech
 * followed by at least 500 ms of quiet. Sentences that merely contain the word never pass.
 * Pure energy bookkeeping over 100 ms frames; no audio is retained.
 */
public final class IsolatedPhraseGate {
    static final int FRAME = 1600;          // 100 ms at 16 kHz
    static final int END_SILENCE = 5;       // frames of quiet that close an utterance
    static final int LATE_HIT = 5;          // frames a late keyword hit may trail the utterance
    static final double MIN_SPEECH_RMS = 250.0;

    private final int maxFrames;
    private double floor;
    private double sum;
    private int filled;
    private boolean inSegment;
    private int segmentFrames;
    private int silenceRun;
    private boolean pending;
    private int lastSegmentFrames = -1;
    private int sinceLastSegment;
    private boolean confirmed;

    public IsolatedPhraseGate(int maxMillis) { maxFrames = Math.max(1, maxMillis / 100); }

    /** Feeds audio; {@code hit} is whether the keyword spotter fired on this chunk. */
    public boolean accept(short[] pcm, int count, boolean hit) {
        if (count < 0 || count > pcm.length) throw new IllegalArgumentException("count");
        for (int i = 0; i < count; i++) {
            sum += (double) pcm[i] * pcm[i];
            if (++filled == FRAME) { frame(Math.sqrt(sum / FRAME)); sum = 0; filled = 0; }
        }
        if (hit) {
            if (inSegment) pending = segmentFrames <= maxFrames;
            else if (lastSegmentFrames > 0 && sinceLastSegment <= LATE_HIT && lastSegmentFrames <= maxFrames) confirmed = true;
        }
        boolean out = confirmed;
        confirmed = false;
        return out;
    }

    private void frame(double rms) {
        boolean speech = rms > Math.max(MIN_SPEECH_RMS, floor * 2.5);
        if (!speech) floor = floor <= 0 ? rms : floor * 0.95 + rms * 0.05;
        else floor += (rms - floor) * 0.002;
        if (speech) {
            if (!inSegment) { inSegment = true; segmentFrames = 0; }
            segmentFrames += silenceRun + 1;
            silenceRun = 0;
            if (segmentFrames > maxFrames) pending = false;
        } else if (inSegment) {
            if (++silenceRun >= END_SILENCE) {
                inSegment = false;
                lastSegmentFrames = segmentFrames;
                sinceLastSegment = 0;
                silenceRun = 0;
                if (pending && segmentFrames <= maxFrames) confirmed = true;
                pending = false;
            }
        } else if (sinceLastSegment < Integer.MAX_VALUE) {
            sinceLastSegment++;
        }
    }

    public void reset() {
        floor = 0; sum = 0; filled = 0; inSegment = false; segmentFrames = 0; silenceRun = 0;
        pending = false; lastSegmentFrames = -1; sinceLastSegment = 0; confirmed = false;
    }
}
