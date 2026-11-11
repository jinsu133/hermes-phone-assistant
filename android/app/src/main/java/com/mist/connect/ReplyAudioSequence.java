package com.mist.connect;

/** All reply segments must finish before follow-up capture or approval arming. */
public final class ReplyAudioSequence {
    public static final int MAX_SEGMENTS = 16;
    private int count = -1;
    private int next = 0;
    private long bytes = 0;
    private boolean inFlight = false;
    private boolean cancelled = false;

    public int next() { return next; }
    public boolean complete() { return !cancelled && !inFlight && count > 0 && next == count; }
    public void cancel() { cancelled = true; }

    public void begin(int index, int total, int length) {
        if (cancelled || inFlight || complete() || index != next || total < 1
                || total > MAX_SEGMENTS || index >= total || (count != -1 && total != count)
                || length <= 0 || bytes + (long) length > VoiceBridgeProtocol.MAX_REPLY_AUDIO) {
            throw new IllegalStateException("tts_segment_invalid");
        }
        count = total;
        bytes += length;
        inFlight = true;
    }

    public void played() {
        if (cancelled || !inFlight) throw new IllegalStateException("tts_segment_incomplete");
        inFlight = false;
        next++;
    }
}
