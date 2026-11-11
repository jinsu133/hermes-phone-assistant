package com.mist.connect;

/** Deterministic, hardware-free local control policy. No IO, clock reads, LLM or
 * authorization. Acoustic classifications are INPUTS from a future validated DSP,
 * not produced by this class. Production must also require authorizesBargeIn(),
 * which remains false. Fixed 10ms/16k capture ticks; no wall-clock sleeps.
 */
public final class DuplexInterruptionPolicy {
    public enum Residual { UNVERIFIED, UNKNOWN, ECHO_ONLY, NEAR_END }
    public enum Run { RUNNING, STOPPING, TERMINAL_UNKNOWN, TERMINAL_CLEAN }
    public enum Decision { MONITOR, CANDIDATE, REQUEST_STOP, HOLD_COMMAND, COMMAND_COMPLETE, BLOCKED, OFF }
    private final PlaybackReference.Scope scope;
    private Decision state=Decision.MONITOR;
    private long nextFrame;
    private int consecutive;
    private boolean endpoint;
    public DuplexInterruptionPolicy(PlaybackReference.Scope scope){this.scope=java.util.Objects.requireNonNull(scope);}
    /** qualifiedStop means residual-gated local STOP evidence, NEVER a raw transcript.
     * Priority: OFF > scope/continuity/reference failure > qualified STOP > candidate
     * > whole endpoint + CLEAN terminal. A stopping ACK is not terminal evidence.
     */
    public synchronized Decision tick(PlaybackReference.Scope expected,long frame,boolean referenceTracked,
            Residual residual,boolean off,boolean qualifiedStop,boolean wholeEndpoint,Run run) {
        if(off){state=Decision.OFF;return state;}
        if(state==Decision.OFF || state==Decision.BLOCKED || state==Decision.COMMAND_COMPLETE)return state;
        if(expected!=scope || frame!=nextFrame || !referenceTracked || residual==null || run==null
                || residual==Residual.UNVERIFIED || run==Run.TERMINAL_UNKNOWN){state=Decision.BLOCKED;return state;}
        nextFrame+=160;
        if(qualifiedStop){state=Decision.REQUEST_STOP;consecutive=0;endpoint=false;return state;}
        if(state==Decision.REQUEST_STOP)return state; // STOP discards command; never silently resume/retry
        if(state==Decision.MONITOR || state==Decision.CANDIDATE) {
            consecutive=residual==Residual.NEAR_END?consecutive+1:0;
            state=consecutive==0?Decision.MONITOR:Decision.CANDIDATE;
            if(consecutive>=3)state=Decision.HOLD_COMMAND;
        }
        if(state==Decision.HOLD_COMMAND) {
            endpoint|=wholeEndpoint;
            if(endpoint && run==Run.TERMINAL_CLEAN)state=Decision.COMMAND_COMPLETE;
        }
        return state;
    }
}
