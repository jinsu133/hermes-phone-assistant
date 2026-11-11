package com.mist.connect;

import java.util.Locale;

/** Pure lifecycle policy. The service owns microphone/output and must actually
 * cancel workers before calling cleaned(). A state change alone is not cancellation.
 * Generation guards are also required at the actuator's point of execution.
 */
public final class VoiceSessionController {
    public enum State { OFF, STARTING, IDLE, LISTENING, BUSY, STOPPING }
    private State state = State.OFF;
    private long generation;
    private boolean unlocked;

    public synchronized State state() { return state; }

    public synchronized long on() {
        if (state == State.OFF) {
            generation++;
            state = State.STARTING;
        }
        return generation;
    }

    /** Call only after actual microphone, wake engine and host readiness checks. */
    public synchronized boolean ready(long ticket) {
        if (state != State.STARTING || ticket != generation) return false;
        state = State.IDLE;
        return true;
    }

    public synchronized boolean wake() {
        if (state != State.IDLE) return false;
        state = State.LISTENING;
        return true;
    }

    public synchronized long beginRequest() {
        if (state != State.LISTENING) throw new IllegalStateException("Not listening");
        generation++;
        state = State.BUSY;
        return generation;
    }

    public synchronized void silenceEnded() {
        // Silence ends capture, not a separately running task.
        if (state == State.LISTENING) state = State.IDLE;
    }

    public synchronized boolean canAct(long ticket) {
        return state == State.BUSY && unlocked && ticket == generation;
    }

    public synchronized boolean finished(long ticket) {
        if (state != State.BUSY || ticket != generation) return false;
        generation++;
        state = State.IDLE;
        return true;
    }

    public synchronized long stop() {
        if (state == State.OFF || state == State.STOPPING) return generation;
        generation++;
        state = State.STOPPING;
        return generation;
    }

    /** Caller must verify all owned capture/playback/task workers are quiescent. */
    public synchronized boolean cleaned(long ticket) {
        if (state != State.STOPPING || ticket != generation) return false;
        state = State.IDLE;
        return true;
    }

    public synchronized void off() {
        generation++;
        state = State.OFF;
        unlocked = false;
    }

    public synchronized long setUnlocked(boolean value) {
        unlocked = value;
        if (!value && state == State.BUSY) return stop();
        return generation;
    }

    /** Proposed Android parity helper; recognition is not speaker authentication. */
    public static boolean isStop(String utterance) {
        if (utterance == null) return false;
        String word = utterance.trim().toLowerCase(Locale.ROOT)
            .replaceAll("^[\\p{P}\\s]+|[\\p{P}\\s]+$", "");
        return word.equals("stop") || word.equals("스탑");
    }
}
