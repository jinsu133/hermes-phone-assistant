package com.mist.connect;

/** Playback-side rate only. PCM sample rate and completion frame counts never change. */
public final class SpeechPlaybackPolicy {
    public static final float DEFAULT_SPEED = 1.4f;
    public static final float MIN_SPEED = 0.8f;
    public static final float MAX_SPEED = 2.0f;
    public static final float PITCH = 1.0f;
    public static final String PREFERENCE = "speech_playback_speed";
    private SpeechPlaybackPolicy() {}
    public static float requireSpeed(float speed) {
        if (!Float.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED)
            throw new IllegalArgumentException("invalid_playback_speed");
        return speed;
    }
    /** A corrupt local preference cannot result in invalid native playback parameters. */
    public static float storedSpeed(float speed) {
        return Float.isFinite(speed) && speed >= MIN_SPEED && speed <= MAX_SPEED ? speed : DEFAULT_SPEED;
    }
}
