package com.mist.connect;

/** Immutable cancellation authority retained across upload/acceptance and async cleanup.
 * A failed/unconfirmed cancellation is sticky for this process, including service recreation.
 * No network or Android APIs; the service owns IO and the retained playback owner.
 */
public final class ScopedVoiceCancellation {
    public static final class Scope {
        public final String sessionGeneration;
        public final String ingressOperationId;
        public final String requestId;
        private Scope(String generation, String operation, String request) {
            sessionGeneration = generation; ingressOperationId = operation; requestId = request;
        }
    }
    private Scope active;
    private Scope cancelling;
    private boolean failed;

    public synchronized boolean blocked() { return failed || cancelling != null; }
    public synchronized boolean current(Scope scope) { return active == scope && !blocked(); }
    public synchronized Scope begin(String generation, String operation) {
        if (blocked() || active != null) throw new IllegalStateException("cancel_cleanup_unconfirmed");
        if (!VoiceBridgeProtocol.validRequestId(generation) || !VoiceBridgeProtocol.validRequestId(operation))
            throw new IllegalArgumentException("invalid_ingress_scope");
        return active = new Scope(generation, operation, null);
    }
    public synchronized Scope accept(Scope upload, String generation, String operation, String request) {
        if (!VoiceBridgeProtocol.validRequestId(request) || !upload.sessionGeneration.equals(generation)
                || !upload.ingressOperationId.equals(operation)) throw new IllegalArgumentException("invalid_acceptance_scope");
        if (active != upload || blocked()) return null; // late acceptance cannot restore cancelled authority
        return active = new Scope(generation, operation, request);
    }
    public synchronized Scope cancelRequest(String request) {
        if (active == null || active.requestId == null || !active.requestId.equals(request)) return null;
        return cancel();
    }
    public synchronized Scope cancel() {
        if (active == null) return null; // never substitute a newer/current request on an old async worker
        Scope snapshot = active;
        active = null;
        cancelling = snapshot;
        return snapshot;
    }
    public synchronized void acknowledge(Scope snapshot, boolean confirmed) {
        if (snapshot != cancelling) return;
        if (!confirmed) failed = true;
        cancelling = null;
    }
    public synchronized void complete(Scope snapshot) {
        if (active == snapshot) active = null;
    }
}
