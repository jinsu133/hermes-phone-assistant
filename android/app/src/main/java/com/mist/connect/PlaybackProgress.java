package com.mist.connect;

/** Process-local, display-only view of the reply audio that is actually playing.
 *
 * The buffered player registers itself only after a real start and unregisters on abort or
 * close, so a stale or replaced player can never drive the overlay. Nothing here grants voice
 * authority; the overlay only reads how far the current audio has played.
 */
public final class PlaybackProgress {
    /** Position and duration of one started player, in milliseconds. */
    public interface Source {
        long positionMs();
        long durationMs();
    }

    private static Source current;

    private PlaybackProgress() {}

    public static synchronized void begin(Source source) {
        current = source;
    }

    /** Clears only the matching source: a newer player that replaced it stays published. */
    public static synchronized void end(Source source) {
        if (current == source) current = null;
    }

    /** Played fraction in [0, 1], or -1 when no reply audio is playing or it cannot be read. */
    public static synchronized float fraction() {
        Source source = current;
        if (source == null) return -1f;
        long duration;
        long position;
        try {
            duration = source.durationMs();
            position = source.positionMs();
        } catch (RuntimeException ignored) {
            return -1f;
        }
        if (duration <= 0 || position < 0) return -1f;
        return Math.min(1f, (float) position / (float) duration);
    }
}
