package com.mist.connect;

/** Memory-only, expiring unlock request. Authentication remains entirely Android-owned. */
public final class UnlockRequestGate {
    private long serial, current, expires;
    private boolean waiting, granted;
    public synchronized long begin(long now, long timeoutMs) {
        current = ++serial; expires = now + timeoutMs; waiting = true; granted = false;
        return current;
    }
    public synchronized boolean pending(long id, long now) {
        if (now >= expires) { waiting = false; granted = false; }
        return id == current && waiting;
    }
    public synchronized boolean complete(long id, long now, boolean deviceUnlocked) {
        if (!pending(id, now)) return false;
        waiting = false; granted = deviceUnlocked;
        return granted;
    }
    public synchronized boolean granted(long id, long now, boolean deviceUnlocked) {
        return id == current && granted && now < expires && deviceUnlocked;
    }
    public synchronized void cancel(long id) {
        if (id == current) { waiting = false; granted = false; }
    }
}
