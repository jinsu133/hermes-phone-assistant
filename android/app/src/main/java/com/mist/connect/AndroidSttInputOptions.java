package com.mist.connect;

import java.util.Map;

/** Explicit per-call experiment; absent option is the byte-for-byte legacy writer path.
 * No persisted preference, diagnostics switch, engine selection or quality claim.
 * Input is still <=30 seconds. Pacing must fit the existing 30s wrapper / 35s client
 * limits: reserve 2s setup and 3s finalization inside that limit, never truncate PCM.
 */
public final class AndroidSttInputOptions {
    public static final AndroidSttInputOptions LEGACY = new AndroidSttInputOptions(false);
    public static final AndroidSttInputOptions REALTIME = new AndroidSttInputOptions(true);
    public static final String HEADER = "x-hermes-stt-pacing";
    public static final String WIRE_MODE = "realtime_20ms";
    private static final long MAX_WAIT_MS = 30_000, SETUP_MS = 2_000, FINAL_MS = 3_000;
    private final boolean paced;
    private AndroidSttInputOptions(boolean paced) { this.paced = paced; }

    /** HTTP parser supplies lower-case unique keys. Ignore ordinary HTTP extensions;
     * reject every unknown option in our reserved namespace, without type coercion. */
    public static AndroidSttInputOptions fromHeaders(Map<String, ?> headers) {
        if (headers == null) throw new IllegalArgumentException("stt_pacing_invalid");
        for (String key : headers.keySet()) {
            if (key == null || (key.startsWith("x-hermes-stt-") && !HEADER.equals(key)))
                throw new IllegalArgumentException("stt_pacing_invalid");
        }
        if (!headers.containsKey(HEADER)) return LEGACY;
        Object value = headers.get(HEADER);
        if (!(value instanceof String) || !WIRE_MODE.equals(value))
            throw new IllegalArgumentException("stt_pacing_invalid");
        return REALTIME;
    }
    public AndroidSttPcmPacer.Mode mode() {
        return paced ? AndroidSttPcmPacer.Mode.REALTIME_20MS : null;
    }
    /** Null deliberately preserves the exact legacy deadline origin and writer. */
    public Budget budget(int bytes, long nowMs) {
        if (!paced) return null;
        if (bytes < 320 || bytes > AndroidSttPcmPacer.MAX_BYTES || (bytes & 1) != 0)
            throw new IllegalArgumentException("stt_pcm_invalid");
        // Validate byte bounds before arithmetic; round UP, including partial blocks.
        long durationMs = ((long) bytes * 1000 + AndroidSttPcmPacer.RATE_BYTES - 1)
                / AndroidSttPcmPacer.RATE_BYTES;
        long waitMs = durationMs + SETUP_MS + FINAL_MS;
        if (waitMs > MAX_WAIT_MS || nowMs < 0 || nowMs > Long.MAX_VALUE - waitMs)
            throw new IllegalArgumentException("stt_pacing_budget_exceeded");
        return new Budget(nowMs, durationMs, nowMs + waitMs);
    }
    public static final class Budget {
        public final long durationMs, deadlineMs;
        private final long originMs;
        private Budget(long originMs, long durationMs, long deadlineMs) {
            this.originMs=originMs; this.durationMs=durationMs; this.deadlineMs=deadlineMs;
        }
        /** Recheck after support/setup, BEFORE startListening. Queue delay is not free. */
        public boolean canStart(long nowMs) {
            return nowMs >= originMs && nowMs <= deadlineMs
                    && deadlineMs - nowMs >= durationMs + FINAL_MS;
        }
    }
}
