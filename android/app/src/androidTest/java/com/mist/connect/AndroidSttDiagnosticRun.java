package com.mist.connect;

import java.util.concurrent.atomic.AtomicBoolean;

/** Instrumentation-only safety gate. No Android, audio or diagnostic payloads. */
public final class AndroidSttDiagnosticRun {
    public enum Stop { NONE, CLEANUP_UNCONFIRMED, OBSERVATION_FAILED, CALL_OR_PROCESSING_FAILED }
    public interface CaseAction { void run(int index) throws Throwable; }
    private final AtomicBoolean active;
    private Stop reason = Stop.NONE;
    private int attempted, calls, completed;
    public AndroidSttDiagnosticRun(AtomicBoolean active) { this.active = active; }
    public void runCases(int planned, CaseAction action) {
        for (int i = 0; i < planned && active.get() && reason == Stop.NONE; i++) {
            attempted++;
            try { action.run(i); completed++; }
            catch (Throwable ignored) { stop(Stop.CALL_OR_PROCESSING_FAILED); }
        }
    }
    public void callStarting() { calls++; }
    public void observe(boolean cleanupConfirmed, boolean observationValid, boolean cleanupError) {
        if (cleanupError || !cleanupConfirmed) stop(Stop.CLEANUP_UNCONFIRMED);
        else if (!observationValid) stop(Stop.OBSERVATION_FAILED);
    }
    public void stop(Stop value) {
        if (reason == Stop.NONE) reason = value;
        active.set(false);
    }
    public Stop reason() { return reason; }
    public int attempted() { return attempted; }
    public int calls() { return calls; }
    public int completed() { return completed; }
}
