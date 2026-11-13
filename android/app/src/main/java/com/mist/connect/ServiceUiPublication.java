package com.mist.connect;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** App-internal DISPLAY ONLY. No authority, IO, dispatch, or subscriber code.
 * All mutation uses the EXISTING cancellation boundary. Playback may enter it in
 * the established playback -> cancellation order; this class never enters playback.
 * Readers pull one immutable volatile slot, outside both ownership locks. */
final class ServiceUiPublication {
    private static final String PROCESS_EPOCH = UUID.randomUUID().toString();
    private static final AtomicLong INCARNATIONS = new AtomicLong();
    enum Mode { UNKNOWN, OFF, MANUAL, HANDSFREE }
    enum Phase { UNKNOWN, PREPARING, WAKE_WAIT, CAPTURING, SUBMITTING, WAITING_REPLY,
        PLAYING, FOLLOW_UP, STOPPING, OFF, BLOCKED }
    enum Capture { UNKNOWN, IDLE, ACTIVE, MONITOR }
    enum CaptureEvent { WAKE_WAIT, FOLLOW_UP, CAPTURING, MONITOR }
    enum Playback { UNKNOWN, IDLE, ACTIVE }
    enum Cleanup { UNKNOWN, PENDING, CONFIRMED, UNCERTAIN }
    enum Wait { NONE, UNLOCK, APPROVAL }
    enum Blocker { NONE, MIC_PERMISSION, PAIRING_MISSING, MIC_BUSY, OWNER_BUSY,
        FOREGROUND_DENIED, HOST_UNAVAILABLE, MODEL_ROUTE_INVALID, PROTOCOL_UNSUPPORTED,
        CONTROLLER_REVOKED, CLEANUP_UNCERTAIN, REQUEST_FAILED }
    /** Display correlation only; never consumed by capture/upload/approval gates. */
    static final class Scope {
        final long generation; final String operation;
        Scope(long generation, String operation) { this.generation=generation; this.operation=operation; }
    }
    static final class Snapshot {
        final String processEpoch; final long incarnation, generation, sequence, sourceAtMillis;
        final boolean acceptedStart, revoked;
        final Mode mode; final Phase phase; final Capture capture; final Playback playback;
        final Cleanup captureCleanup, playbackCleanup, localCleanup, remoteCleanup;
        final boolean remoteJoined;
        final String operationId, requestId, publicFinalPreview;
        final Wait waiting; final Blocker blocker;
        Snapshot(ServiceUiPublication owner, Draft d) {
            processEpoch=PROCESS_EPOCH; incarnation=owner.incarnation; generation=d.generation;
            sequence=++owner.sequence; sourceAtMillis=owner.clock.getAsLong();
            acceptedStart=d.acceptedStart; revoked=d.revoked; mode=d.mode; phase=d.phase;
            capture=d.capture; playback=d.playback; captureCleanup=d.captureCleanup;
            playbackCleanup=d.playbackCleanup; localCleanup=d.localCleanup; remoteCleanup=d.remoteCleanup;
            remoteJoined=d.remoteJoined; operationId=d.operationId; requestId=d.requestId;
            publicFinalPreview=d.publicFinalPreview;
            waiting=d.waiting; blocker=d.blocker;
        }
    }
    private static final class Draft {
        long generation; boolean acceptedStart, revoked, remoteJoined=true;
        Mode mode=Mode.UNKNOWN; Phase phase=Phase.UNKNOWN;
        Capture capture=Capture.UNKNOWN; Playback playback=Playback.UNKNOWN;
        Cleanup captureCleanup=Cleanup.UNKNOWN, playbackCleanup=Cleanup.UNKNOWN,
            localCleanup=Cleanup.UNKNOWN, remoteCleanup=Cleanup.UNKNOWN;
        String operationId, requestId, publicFinalPreview; Wait waiting=Wait.NONE; Blocker blocker=Blocker.NONE;
        Draft() {}
        Draft(Snapshot s) {
            generation=s.generation; acceptedStart=s.acceptedStart; revoked=s.revoked; mode=s.mode; phase=s.phase;
            capture=s.capture; playback=s.playback; captureCleanup=s.captureCleanup;
            playbackCleanup=s.playbackCleanup; localCleanup=s.localCleanup; remoteCleanup=s.remoteCleanup;
            remoteJoined=s.remoteJoined; operationId=s.operationId; requestId=s.requestId; publicFinalPreview=s.publicFinalPreview;
            waiting=s.waiting; blocker=s.blocker;
        }
    }
    private final Object gate;
    private final LongSupplier clock;
    private final long incarnation=INCARNATIONS.incrementAndGet();
    private long sequence;
    private String privacyFencedRequest;
    private volatile Snapshot latest;
    ServiceUiPublication(Object existingCancellationGate, LongSupplier elapsedRealtime) {
        gate=existingCancellationGate; clock=elapsedRealtime;
        synchronized(gate) { latest=new Snapshot(this,new Draft()); }
    }
    Snapshot read() { return latest; }
    private void commit(Draft d) { latest=new Snapshot(this,d); }
    private boolean same(Scope s) { return s!=null && latest.acceptedStart && s.generation==latest.generation; }
    private boolean current(Scope s) {
        return same(s) && !latest.revoked && (s.operation==null || s.operation.equals(latest.operationId));
    }
    Scope sessionScope() { Snapshot s=latest; return new Scope(s.generation,null); }
    Scope requestScope() { Snapshot s=latest; return new Scope(s.generation,s.operationId); }
    Scope start(int acceptedStartId, Mode mode) {
        synchronized(gate) {
            Draft d=new Draft(); d.generation=acceptedStartId; d.acceptedStart=true;
            d.mode=mode; d.phase=Phase.PREPARING; d.capture=Capture.IDLE; d.playback=Playback.IDLE;
            d.captureCleanup=Cleanup.CONFIRMED; d.playbackCleanup=Cleanup.CONFIRMED;
            d.localCleanup=Cleanup.PENDING; d.remoteCleanup=Cleanup.CONFIRMED;
            commit(d); return new Scope(d.generation,null);
        }
    }
    void refused(Blocker reason) {
        synchronized(gate) {
            if(latest.acceptedStart && (!latest.revoked || latest.blocker==Blocker.CLEANUP_UNCERTAIN)) return;
            Draft d=new Draft(latest); d.phase=Phase.BLOCKED; d.blocker=reason; commit(d);
        }
    }
    void phase(Scope s, Phase phase) {
        synchronized(gate) { if(!current(s))return; Draft d=new Draft(latest);
            if(d.blocker==Blocker.NONE)d.phase=phase; commit(d); }
    }
    /** Liveness only: while the service is running, re-stamp the unchanged state so a long
     * wait for the host (an app opening, a slow reply) does not look like a dead service.
     * Throttled to stamps older than minAgeMillis; never changes phase or authority. */
    boolean heartbeat(Scope s, long minAgeMillis) {
        synchronized(gate) {
            if(!same(s) || latest.revoked || latest.phase==Phase.OFF || latest.phase==Phase.STOPPING) return false;
            if(clock.getAsLong()-latest.sourceAtMillis < minAgeMillis) return false;
            commit(new Draft(latest)); return true;
        }
    }
    void waitFor(Scope s, Wait waiting) {
        synchronized(gate) { if(!current(s))return; Draft d=new Draft(latest); d.waiting=waiting; commit(d); }
    }
    void blocked(Scope s, Blocker reason) {
        synchronized(gate) { if(!same(s))return; Draft d=new Draft(latest);
            if(s.operation!=null && !s.operation.equals(d.operationId))return;
            if(d.blocker==Blocker.CLEANUP_UNCERTAIN)return;
            d.blocker=reason; d.phase=Phase.BLOCKED; commit(d); }
    }
    Scope beginRequest(Scope s, String operation) {
        synchronized(gate) { if(!current(s))return null; Draft d=new Draft(latest);
            privacyFencedRequest=null;
            d.operationId=operation; d.requestId=null; d.publicFinalPreview=null; d.waiting=Wait.NONE;
            d.remoteCleanup=Cleanup.PENDING; d.phase=Phase.SUBMITTING; commit(d);
            return new Scope(d.generation,operation); }
    }
    void acceptRequest(Scope s, String request) {
        synchronized(gate) { if(!current(s))return; Draft d=new Draft(latest);
            d.requestId=request; d.phase=Phase.WAITING_REPLY; commit(d); }
    }
    /** Accept only a final reply for the exact currently owned request. Display only. */
    void publishFinal(Scope s, String request, String text) {
        synchronized(gate) {
            if (!current(s) || s.operation == null || request == null ||
                !request.equals(latest.requestId) || request.equals(privacyFencedRequest) || text == null) return;
            String cleaned = text.replaceAll("(?is)<(?:think|analysis|reasoning)\\b[^>]*>.*?(?:</(?:think|analysis|reasoning)\\s*>|\\z)", "[private section omitted]")
                .replaceAll("(?s)```.*?(?:```|\\z)", "[code omitted]")
                .replaceAll("(?i)(?:sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._~+/-]{12,}|(?:api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;]+)", "[redacted]")
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
            // Match the service's bounded reply text while retaining its full visible answer.
            int count = cleaned.codePointCount(0, cleaned.length());
            if (count > 4000) cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, 4000));
            Draft d = new Draft(latest); d.publicFinalPreview = cleaned.isEmpty() ? null : cleaned; commit(d);
        }
    }
    /** Screen/permission loss retires this request's preview, including late replies. */
    void clearPublicPreview() {
        synchronized(gate) {
            privacyFencedRequest = latest.requestId;
            if (latest.publicFinalPreview != null) {
                Draft d = new Draft(latest); d.publicFinalPreview = null; commit(d);
            }
        }
    }
    void retireRequest(Scope s, boolean completed) {
        synchronized(gate) { if(!same(s) || s.operation==null || !s.operation.equals(latest.operationId))return;
            Draft d=new Draft(latest); d.operationId=null; d.waiting=Wait.NONE;
            // Display-only retention until FOLLOW_UP ends; OFF, lock and the next
            // request still revoke the preview and the old operation is retired.
            if(!completed || d.mode!=Mode.HANDSFREE) { d.requestId=null; d.publicFinalPreview=null; }
            d.remoteCleanup=completed?Cleanup.CONFIRMED:Cleanup.PENDING;
            if(!d.revoked && d.blocker==Blocker.NONE)d.phase=Phase.PREPARING; commit(d); }
    }
    void captureEvent(Scope s, CaptureEvent event) {
        synchronized(gate) { if(!current(s))return; Draft d=new Draft(latest);
            if(event==CaptureEvent.WAKE_WAIT && d.operationId==null) {
                d.requestId=null; d.publicFinalPreview=null;
            }
            if(d.blocker==Blocker.REQUEST_FAILED)d.blocker=Blocker.NONE;
            d.capture=event==CaptureEvent.MONITOR?Capture.MONITOR:Capture.ACTIVE;
            d.captureCleanup=Cleanup.PENDING;
            if(d.blocker==Blocker.NONE && event!=CaptureEvent.MONITOR)
                d.phase=event==CaptureEvent.WAKE_WAIT?Phase.WAKE_WAIT:event==CaptureEvent.FOLLOW_UP?Phase.FOLLOW_UP:Phase.CAPTURING;
            commit(d); }
    }
    void captureClosed(Scope s, boolean confirmed) {
        synchronized(gate) { if(!same(s))return; Draft d=new Draft(latest);
            d.capture=confirmed?Capture.IDLE:Capture.UNKNOWN;
            d.captureCleanup=d.captureCleanup==Cleanup.UNCERTAIN?Cleanup.UNCERTAIN:confirmed?Cleanup.CONFIRMED:Cleanup.UNCERTAIN;
            if(!confirmed) { d.blocker=Blocker.CLEANUP_UNCERTAIN; d.phase=Phase.BLOCKED; }
            else if(!d.revoked && d.blocker==Blocker.NONE &&
                (d.phase==Phase.CAPTURING || d.phase==Phase.WAKE_WAIT || d.phase==Phase.FOLLOW_UP)) d.phase=Phase.PREPARING;
            commit(d); }
    }
    void playback(Scope s, Playback value, Cleanup cleanup) {
        synchronized(gate) { if(!same(s))return;
            if(s.operation!=null && latest.operationId!=null && !s.operation.equals(latest.operationId))return;
            if(value==Playback.ACTIVE && !current(s))return;
            Draft d=new Draft(latest); d.playback=value; d.playbackCleanup=cleanup;
            if(cleanup==Cleanup.UNCERTAIN) { d.blocker=Blocker.CLEANUP_UNCERTAIN; d.phase=Phase.BLOCKED; }
            else if(!d.revoked && d.blocker==Blocker.NONE) {
                if(value==Playback.ACTIVE)d.phase=Phase.PLAYING;
                else if(d.phase==Phase.PLAYING)d.phase=d.requestId!=null?Phase.WAITING_REPLY:Phase.PREPARING;
            }
            commit(d); }
    }
    void off(Scope s, boolean sessionOff) {
        synchronized(gate) { if(!same(s))return; Draft d=new Draft(latest);
            if(sessionOff && d.revoked)return; // repeated OFF/destroy cannot erase actual settlement
            d.revoked=d.revoked || sessionOff; if(sessionOff)d.mode=Mode.OFF;
            d.operationId=null; d.requestId=null; d.publicFinalPreview=null; d.waiting=Wait.NONE;
            if(d.blocker==Blocker.NONE)d.phase=Phase.STOPPING;
            d.localCleanup=Cleanup.PENDING; commit(d); }
    }
    void remote(Scope s, Cleanup cleanup, boolean joined) {
        synchronized(gate) { if(!same(s))return; Draft d=new Draft(latest);
            // Late old request ACK must not overwrite a newer request's pending state.
            if(s.operation!=null && d.operationId!=null && !s.operation.equals(d.operationId))return;
            d.remoteCleanup=d.remoteCleanup==Cleanup.UNCERTAIN?Cleanup.UNCERTAIN:cleanup; d.remoteJoined=joined;
            if(cleanup==Cleanup.UNCERTAIN){ d.blocker=Blocker.CLEANUP_UNCERTAIN; d.phase=Phase.BLOCKED; }
            commit(d); }
    }
    void settle(Scope s, boolean localConfirmed, boolean remoteClear, boolean joined, boolean uncertain) {
        synchronized(gate) { if(!same(s))return; Draft d=new Draft(latest);
            d.localCleanup=d.localCleanup==Cleanup.UNCERTAIN || uncertain?Cleanup.UNCERTAIN:localConfirmed?Cleanup.CONFIRMED:Cleanup.PENDING;
            if(remoteClear && d.operationId==null && d.remoteCleanup!=Cleanup.UNCERTAIN)d.remoteCleanup=Cleanup.CONFIRMED;
            d.remoteJoined=joined;
            if(d.localCleanup==Cleanup.UNCERTAIN || d.remoteCleanup==Cleanup.UNCERTAIN || uncertain || d.captureCleanup==Cleanup.UNCERTAIN || d.playbackCleanup==Cleanup.UNCERTAIN) {
                d.blocker=Blocker.CLEANUP_UNCERTAIN; d.phase=Phase.BLOCKED;
            } else if(d.revoked && localConfirmed && remoteClear && joined && d.blocker==Blocker.NONE) {
                d.phase=Phase.OFF; d.mode=Mode.OFF;
            }
            commit(d);
        }
    }
}
