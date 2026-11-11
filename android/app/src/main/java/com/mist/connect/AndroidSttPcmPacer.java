package com.mist.connect;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Opt-in diagnostic scheduling only. No PCM ownership, IO, threads or completion authority.
 * Absolute sample-clock deadlines avoid accumulating decode/write time as extra silence.
 * Catch-up never skips bytes. The real writer alone accounts successful writes/normal close.
 */
public final class AndroidSttPcmPacer {
    public enum Mode { FAST, REALTIME_20MS }
    static final int RATE_BYTES = 32000;
    static final int MAX_BYTES = RATE_BYTES * 30;
    static final long POLL_NS = 20_000_000L;
    interface Clock { long now(); void sleep(long nanos) throws InterruptedException; }
    private final Mode mode;
    private final Clock clock;
    private final long origin;

    public AndroidSttPcmPacer(Mode mode) {
        this(mode, new Clock() {
            public long now() { return System.nanoTime(); }
            public void sleep(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
        });
    }
    AndroidSttPcmPacer(Mode mode, Clock clock) {
        if (mode == null || clock == null) throw new IllegalArgumentException("pacing_input");
        this.mode = mode; this.clock = clock; this.origin = clock.now();
    }
    // Matched chunk size isolates pacing; production (null seam) remains 4096 bytes.
    public int chunkBytes() { return 640; }
    /** Deadline is the END of the next block: first 640 bytes at 20ms, no leading burst.
     * Invoked outside completion/trace locks; checks revoke before AND after every wait.
     * Cancellation does not manufacture EOF or complete a partial input successfully.
     */
    public boolean beforeWrite(int supplied, int count, BooleanSupplier allowed) throws InterruptedException {
        if (supplied < 0 || count <= 0 || count > chunkBytes() || (supplied & 1) != 0
                || (count & 1) != 0 || supplied > MAX_BYTES - count || allowed == null)
            throw new IllegalArgumentException("pacing_range");
        long targetElapsed = mode == Mode.FAST ? 0 : ((long) supplied + count) * 1_000_000_000L / RATE_BYTES;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!allowed.getAsBoolean()) return false;
            long remaining = targetElapsed - (clock.now() - origin);
            if (remaining <= 0) return true;
            clock.sleep(Math.min(remaining, POLL_NS));
        }
    }
}
