package com.mist.connect;
/** Latest-only progress cursor. Terminal status permanently invalidates this request. */
public final class VoiceEventQueue {
    private final String request;
    private long seen = 0;
    private long current = 0;
    private boolean terminal = false;
    public VoiceEventQueue(String request) { this.request = request; }
    public synchronized boolean accept(String owner, long seq) {
        if(terminal || !request.equals(owner) || seq <= seen) return false;
        seen=seq; current=seq; return true;
    }
    public synchronized boolean active(String owner, long seq) {
        return !terminal && request.equals(owner) && seq==current && seq>0;
    }
    /** Host-confirmed expiry must not reset the duplicate/replay high-water mark. */
    public synchronized void invalidate() { current=0; }
    public synchronized long cursor() { return seen; }
    public synchronized void finish() { terminal=true; }
}
