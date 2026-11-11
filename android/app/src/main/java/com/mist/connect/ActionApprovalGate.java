package com.mist.connect;
/** Single-use approval for one immutable action, never a caller-controlled boolean. */
public final class ActionApprovalGate {
    private final String requestId;
    private final long expires;
    private boolean approved, consumed, cancelled;
    public ActionApprovalGate(String requestId, long expires) { this.requestId=requestId; this.expires=expires; }
    public synchronized void userDecision(boolean allow, long now) {
        if (!consumed && !cancelled && now < expires) { approved=allow; cancelled=!allow; }
    }
    public synchronized void cancel(String id) { if (requestId.equals(id)) { cancelled=true; approved=false; } }
    public synchronized boolean waiting(long now) { return !approved && !cancelled && !consumed && now < expires; }
    public synchronized boolean consume(String id, long now, boolean contextValid) {
        if (!requestId.equals(id) || !approved || cancelled || consumed || now >= expires || !contextValid) return false;
        consumed=true; return true;
    }
}
