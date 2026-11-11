package com.mist.connect;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-local authority used by the real listener and writer. No native IO,
 * executors, waits or caller callbacks run under this object's monitor.
 * Successful pipe writes are not evidence of recognizer consumption.
 */
public final class AndroidSttCompletion {
    private final int inputBytes;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final ArrayList<String> segments = new ArrayList<>();
    private int segmentChars, suppliedBytes;
    private String candidateText, firstFailure;
    private boolean normalClose, writerFailed, cancelled, cleanupSeen, cleanupConfirmed, sealed;

    public AndroidSttCompletion(int inputBytes) { this.inputBytes = inputBytes; }
    public boolean isTerminal() { return terminal.get(); }
    public synchronized boolean isActive() { return !cancelled && !sealed; }

    /** False only when the text bound is exceeded. Late segments are ignored. */
    public synchronized boolean segment(String text, boolean nonBlank) {
        if (sealed || terminal.get()) return true;
        // Keep Kotlin's existing isNotBlank semantics at the call site (including Unicode).
        // As before, separators are checked by the terminal text-length guard.
        int size = segmentChars + text.length();
        if (size > 6000) return false;
        if (nonBlank) { segments.add(text); segmentChars = size; }
        return true;
    }
    public synchronized String segmentText() { return String.join(" ", segments); }

    /** The first native terminal is a candidate, never permission to return text.
     * The wrapper supplies fixed NO_MATCH/timeout/empty-final errors. */
    public synchronized boolean offer(String text, String error, AndroidSttTrace.Terminal reason,
                                      int code, AndroidSttTrace trace) {
        if (sealed) return false;
        if (reason == AndroidSttTrace.Terminal.CANCELLED) cancel();
        if (!AndroidSttTrace.claim(terminal, trace, reason, code)) return false;
        candidateText = text;
        if (error != null && firstFailure == null) firstFailure = error;
        return true;
    }
    public synchronized void cancel() {
        if (sealed) return;
        cancelled = true;
        if (firstFailure == null) firstFailure = "stt_cancelled";
    }
    public synchronized void supplied(int bytes) {
        if (!sealed) suppliedBytes += bytes;
    }
    /** Called only after the output stream's normal close has returned. */
    public synchronized void normalClose() { if (!sealed) normalClose = true; }
    public synchronized void writerFailed() {
        if (sealed) return;
        writerFailed = true;
        if (firstFailure == null) firstFailure = "stt_input_incomplete";
    }
    public synchronized void cleanup(boolean confirmed) {
        if (!sealed) { cleanupSeen = true; cleanupConfirmed = confirmed; }
    }
    public static final class Outcome {
        public final String text, error;
        public final boolean cleanupConfirmed;
        private Outcome(String text, String error, boolean cleanupConfirmed) {
            this.text=text; this.error=error; this.cleanupConfirmed=cleanupConfirmed;
        }
    }
    /** Called only after bounded joins and independent cleanup attempts.
     * Cleanup uncertainty always retains the busy owner, even if a prior error
     * is preserved in this response. Never return partial text on any failure. */
    public synchronized Outcome seal() {
        sealed = true;
        String error = firstFailure;
        if (error == null && cancelled) error = "stt_cancelled";
        if (error == null && (!terminal.get() || candidateText == null)) error = "stt_timeout";
        if (error == null && (suppliedBytes != inputBytes || !normalClose || writerFailed))
            error = "stt_input_incomplete";
        if (error == null && (!cleanupSeen || !cleanupConfirmed)) error = "stt_cleanup_unconfirmed";
        String text = error == null ? candidateText : "";
        segments.clear(); candidateText = null;
        return new Outcome(text, error, cleanupSeen && cleanupConfirmed);
    }
}
