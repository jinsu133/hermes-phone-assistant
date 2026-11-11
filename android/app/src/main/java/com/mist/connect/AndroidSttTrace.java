package com.mist.connect;

import java.util.concurrent.atomic.AtomicLong;

/** Request-local, memory-only metadata. Never accepts PCM, text, tokens or Throwables.
 * Call every observation/serialization through guard; this object owns no recognizer,
 * latch, pipe, worker or admission state. Fixed ASCII schema bounds JSON bytes.
 * RMS/buffer callbacks are counters only. Supply progress is a counter, not an event.
 */
public final class AndroidSttTrace {
    public enum Kind {
        SUPPORT_BEGIN, SUPPORT_END, START_LISTENING, START_RETURNED,
        WRITER_START, WRITER_EOF, WRITER_END, CB_READY, CB_BEGIN, CB_END,
        CB_PARTIAL, CB_EVENT, CB_SEGMENT, CB_SEGMENT_END, CB_RESULTS, CB_ERROR,
        CANCEL, DESTROY_BEGIN, DESTROY_END, PIPE_CLOSE, CLEANUP
    }
    public enum Terminal {
        NONE, RESULT, SEGMENT_END, ERROR, CANCELLED, TIMEOUT, WRONG_THREAD,
        PCM_INVALID, BUSY, PIPE_UNAVAILABLE, ON_DEVICE_UNAVAILABLE, MODEL_MISSING,
        TEXT_LIMIT, SOURCE_IO, START_FAILED, SUPPORT_ERROR, SETUP_FAILED, PACING_BUDGET
    }
    public static final int MAX_EVENTS = 64;
    public static final int MAX_JSON_BYTES = 24576;
    private static final AtomicLong IDS = new AtomicLong();
    private final long requestId = IDS.incrementAndGet(); // process-local, not a remote correlation
    private final long origin = System.nanoTime();
    private final int inputBytes;
    private final Entry[] events = new Entry[MAX_EVENTS];
    private final Runnable beforeSerialize; // package-private fault injection; no production observer
    private int count;
    private long sequence, dropped, callbackCount, segmentCount, segmentChars, rmsCount, bufferCount;
    private long writerBytes, lastMs, writerStartMs, writerEofMs, writerEndMs, cleanupMs;
    private long destroyBeginMs, destroyEndMs;
    private boolean destroyBegan, destroyEnded, destroySucceeded;
    private int writeInFlightBytes, terminalWriteInFlightBytes;
    private boolean admitted, writerStarted, normalEof, writerEnded, writerFailed;
    private boolean cleanupSeen, cleanupConfirmed, writerAliveAtCleanup, destroyWaitConfirmed;
    private boolean sealed;
    private Terminal terminal = Terminal.NONE;
    private long terminalMs, terminalSequence, terminalBytes;
    private int terminalCode;
    private boolean terminalEof, terminalAtomicClaim;
    private long claimBeforeMs;
    private String frozen;
    // Fixed-size totals survive bounded event overflow; content never enters this object.
    private final long[] nativeCounts = new long[Kind.values().length];
    private final long[] nativeChars = new long[Kind.values().length];
    private boolean completionSeen, completionSuccess, completionCleanup;
    private int completionChars;

    private static final class Entry {
        final long seq, ms, a, b; final Kind kind; final boolean flag;
        Entry(long seq,long ms,Kind kind,long a,long b,boolean flag) {
            this.seq=seq;this.ms=ms;this.kind=kind;this.a=a;this.b=b;this.flag=flag;
        }
        void append(StringBuilder out) {
            out.append("{\"seq\":").append(seq).append(",\"t_ms\":").append(ms)
                .append(",\"kind\":\"").append(kind.name()).append("\",\"a\":").append(a)
                .append(",\"b\":").append(b).append(",\"flag\":").append(flag).append('}');
        }
    }
    public AndroidSttTrace(int inputBytes) { this(inputBytes, null); }
    AndroidSttTrace(int inputBytes, Runnable beforeSerialize) {
        this.inputBytes=Math.max(0,inputBytes);this.beforeSerialize=beforeSerialize;
    }
    /** A failing observer is never allowed to skip result publication or cleanup. */
    public static void guard(Runnable action) { try { action.run(); } catch (Throwable ignored) {} }
    /** CAS and metadata snapshot share the writer-observation monitor. No native work
     * (write/close/destroy/join/result publication) is executed while holding it.
     * Observer exceptions are caught independently; never retry the result CAS.
     */
    public static boolean claim(java.util.concurrent.atomic.AtomicBoolean finished,
                                AndroidSttTrace trace, Terminal kind, int code) {
        return claim(finished, trace, kind, code, null, null);
    }
    // Test-only barriers/faults. Production never supplies callbacks here.
    static boolean claim(java.util.concurrent.atomic.AtomicBoolean finished,
                         AndroidSttTrace trace, Terminal kind, int code,
                         Runnable before, Runnable after) {
        if (trace == null) return finished.compareAndSet(false, true);
        synchronized (trace) {
            long beforeMs = 0;
            try {
                beforeMs = trace.now();
                if (before != null) before.run();
            } catch (Throwable ignored) { /* still attempt the original CAS once */ }
            boolean won = finished.compareAndSet(false, true);
            if (won) {
                try {
                    if (after != null) after.run();
                    if (!trace.sealed && trace.terminal == Terminal.NONE) {
                        trace.terminal(kind, code);
                        trace.terminalAtomicClaim = trace.terminal != Terminal.NONE;
                        trace.claimBeforeMs = beforeMs;
                    }
                } catch (Throwable ignored) { /* still publish the winning result */ }
            }
            return won;
        }
    }
    public long id() { return requestId; }
    private long now() {
        lastMs=Math.max(lastMs, Math.max(0,(System.nanoTime()-origin)/1000000L));return lastMs;
    }
    private static long increment(long n) { return n==Long.MAX_VALUE?n:n+1; }
    private static long add(long a,long b) { return b<=0?a:(a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b); }
    public synchronized void admitted() { if (!sealed) admitted=true; }
    public synchronized void event(Kind kind,long a,long b,boolean flag) {
        if (sealed || kind==null) return;
        if(kind==Kind.DESTROY_BEGIN) { destroyBegan=true;destroyBeginMs=now(); }
        if(kind==Kind.DESTROY_END) { destroyEnded=true;destroySucceeded=flag;destroyEndMs=now(); }
        if (kind.name().startsWith("CB_")) {
            callbackCount=increment(callbackCount);
            nativeCounts[kind.ordinal()]=increment(nativeCounts[kind.ordinal()]);
            if (kind==Kind.CB_SEGMENT || kind==Kind.CB_RESULTS || kind==Kind.CB_PARTIAL)
                nativeChars[kind.ordinal()]=add(nativeChars[kind.ordinal()],Math.max(0,b));
        }
        if (kind==Kind.CB_SEGMENT) { segmentCount=increment(segmentCount);segmentChars=add(segmentChars,Math.max(0,b)); }
        append(kind,a,b,flag);
    }
    private void append(Kind kind,long a,long b,boolean flag) {
        sequence=increment(sequence);
        long ms=now();
        if (count<MAX_EVENTS) events[count++]=new Entry(sequence,ms,kind,a,b,flag);
        else dropped=increment(dropped);
    }
    public synchronized void frameCallback(boolean rms) {
        if(sealed)return;
        callbackCount=increment(callbackCount);
        if(rms)rmsCount=increment(rmsCount);else bufferCount=increment(bufferCount);
        // Intentionally no timeline entries, values, frame lengths, or RMS levels.
    }
    public synchronized void writerStarted() {
        if(sealed)return;writerStarted=true;writerStartMs=now();append(Kind.WRITER_START,0,0,false);
    }
    /** No event allocation. Counts are observed successful writes, NOT engine consumption.
     * A write in flight may complete natively before its observation acquires this monitor.
     * Such unobserved bytes stay excluded; in-flight is uncertainty, not exact native state.
     * An in-flight write makes terminal byte count a lower bound;
     * never hold this monitor across the real blocking pipe write. */
    public synchronized void writeBegin(int bytes) {
        if(!sealed)writeInFlightBytes=Math.max(0,bytes);
    }
    /** Only after output.write returns normally; bytes accepted by pipe, NOT consumed by engine. */
    public synchronized void supplied(int bytes) {
        if(!sealed && bytes>0) {writerBytes=Math.min(inputBytes,add(writerBytes,bytes));writeInFlightBytes=0;}
    }
    /** Only after all input writes AND successful AutoCloseOutputStream close. */
    public synchronized void writerEof() {
        if(sealed)return;
        if(writerStarted && writerBytes==inputBytes) {normalEof=true;writerEofMs=now();append(Kind.WRITER_EOF,writerBytes,0,true);}
    }
    public synchronized void writerEnd(boolean failed) {
        if(sealed)return;writerEnded=true;writerFailed=failed;writerEndMs=now();
        writeInFlightBytes=0;append(Kind.WRITER_END,writerBytes,0,failed);
    }
    /** First successfully claimed terminal, retained independently of the bounded event array. */
    public synchronized void terminal(Terminal kind,int code) {
        if(sealed || terminal!=Terminal.NONE || kind==null || kind==Terminal.NONE)return;
        terminal=kind;terminalCode=code;terminalMs=now();sequence=increment(sequence);
        terminalSequence=sequence;terminalBytes=writerBytes;terminalEof=normalEof;
        terminalWriteInFlightBytes=writeInFlightBytes;
    }
    public synchronized void cleanup(boolean confirmed,boolean writerAlive,boolean destroyWait) {
        if(sealed)return;
        cleanupSeen=true;cleanupConfirmed=confirmed;writerAliveAtCleanup=writerAlive;destroyWaitConfirmed=destroyWait;
        cleanupMs=now();
        append(Kind.CLEANUP,writerBytes,0,confirmed);
    }
    /** Observes the actual immutable seal result, never a competing completion decision. */
    public synchronized void completionSnapshot(boolean success,int chars,boolean cleanup) {
        if(sealed || completionSeen)return;
        completionSeen=true;completionSuccess=success;completionChars=Math.max(0,chars);completionCleanup=cleanup;
    }
    public synchronized int eventCount() { return count; }
    public synchronized boolean isSealed() { return sealed; }
    synchronized boolean timesNondecreasing() {
        for(int i=1;i<count;i++)if(events[i].ms<events[i-1].ms)return false;return true;
    }
    /** Seal before serialization, including if serialization fails. Late callbacks are ignored.
     * 64 entries * <220 ASCII bytes + <4096 bytes summary < 24576 bytes.
     * No unbounded strings, collections, counters-as-strings, or payload-derived keys.
     */
    public synchronized String sealJson() {
        if(frozen!=null)return frozen;
        if(sealed)return null;
        sealed=true;
        if(beforeSerialize!=null)beforeSerialize.run();
        StringBuilder b=new StringBuilder(MAX_JSON_BYTES);
        b.append("{\"schema\":2,\"request_id\":").append(requestId)
            .append(",\"sealed\":true,\"event_cap\":").append(MAX_EVENTS)
            .append(",\"json_byte_cap\":").append(MAX_JSON_BYTES)
            .append(",\"input_bytes\":").append(inputBytes)
            .append(",\"elapsed_ms\":").append(now())
            .append(",\"admitted\":").append(admitted)
            .append(",\"dropped_events\":").append(dropped)
            .append(",\"callback_count\":").append(callbackCount)
            .append(",\"rms_callbacks\":").append(rmsCount)
            .append(",\"buffer_callbacks\":").append(bufferCount)
            .append(",\"segment_count\":").append(segmentCount)
            .append(",\"segment_chars\":").append(segmentChars)
            .append(",\"writer_start_ms\":").append(writerStartMs)
            .append(",\"writer_eof_ms\":").append(writerEofMs)
            .append(",\"writer_end_ms\":").append(writerEndMs)
            .append(",\"cleanup_ms\":").append(cleanupMs)
            .append(",\"destroy_began\":").append(destroyBegan)
            .append(",\"destroy_ended\":").append(destroyEnded)
            .append(",\"destroy_succeeded\":").append(destroySucceeded)
            .append(",\"destroy_begin_ms\":").append(destroyBeginMs)
            .append(",\"destroy_end_ms\":").append(destroyEndMs)
            .append(",\"writer_started\":").append(writerStarted)
            .append(",\"writer_bytes\":").append(writerBytes)
            .append(",\"normal_eof\":").append(normalEof)
            .append(",\"writer_ended\":").append(writerEnded)
            .append(",\"writer_failed\":").append(writerFailed)
            .append(",\"cleanup_required\":").append(admitted)
            .append(",\"cleanup_seen\":").append(cleanupSeen)
            .append(",\"cleanup_confirmed\":").append(cleanupConfirmed)
            .append(",\"writer_alive_at_cleanup\":").append(writerAliveAtCleanup)
            .append(",\"destroy_wait_confirmed\":").append(destroyWaitConfirmed)
            .append(",\"first_terminal\":{\"kind\":\"").append(terminal.name())
            .append("\",\"seq\":").append(terminalSequence).append(",\"t_ms\":").append(terminalMs)
            .append(",\"atomic_claim\":").append(terminalAtomicClaim)
            .append(",\"claim_before_ms\":").append(claimBeforeMs)
            .append(",\"claim_after_ms\":").append(terminalAtomicClaim ? terminalMs : 0)
            .append(",\"code\":").append(terminalCode).append(",\"bytes\":").append(terminalBytes)
            .append(",\"write_in_flight_bytes\":").append(terminalWriteInFlightBytes)
            .append(",\"normal_eof\":").append(terminalEof).append("},\"events\":[");
        for(int i=0;i<count;i++){if(i!=0)b.append(',');events[i].append(b);}
        b.append("],\"native_callbacks\":{");
        boolean first=true;
        for(Kind kind:Kind.values())if(kind.name().startsWith("CB_")) {
            if(!first)b.append(',');first=false;
            b.append('"').append(kind.name()).append("\":{\"count\":")
                .append(nativeCounts[kind.ordinal()]).append(",\"chars\":")
                .append(nativeChars[kind.ordinal()]).append('}');
        }
        b.append("},\"completion_snapshot\":{\"seen\":").append(completionSeen)
            .append(",\"success\":").append(completionSuccess)
            .append(",\"text_chars\":").append(completionChars)
            .append(",\"cleanup_confirmed\":").append(completionCleanup).append("}}");
        if(b.length()>MAX_JSON_BYTES)throw new IllegalStateException();
        frozen=b.toString();return frozen;
    }
}
