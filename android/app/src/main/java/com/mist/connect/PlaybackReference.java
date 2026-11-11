package com.mist.connect;

import java.util.Arrays;

/** Volatile PCM accepted by the real sink plus Android presentation estimates.
 * NOT a speaker recording, AEC, speech decision, approval, or listening ticket.
 * PCM16LE/24k/mono/1x only; bounded 2s ring. No extrapolation of unknown output.
 * Explicit verified cessation can supply bounded digital silence after its boundary;
 * ordinary Android presentation estimates alone NEVER establish that boundary.
 * Every failed invariant erases the ring and is sticky for this audio identity.
 */
public final class PlaybackReference {
    public enum Reason { WARMUP, TRACKING_UNVERIFIED, SPEED_UNSUPPORTED, WRITE_INVALID,
        OVERFLOW, TIMESTAMP_INVALID, TIMESTAMP_LOST, ROUTE_UNSUPPORTED, ROUTE_CHANGED, STOP_UNPROVEN, REVOKED }
    public static class Scope {
        public final String request, audio;
        public final long generation;
        public final boolean requestRender;
        public Scope(String request,String audio,long generation) {
            this(request,audio,generation,false);
        }
        protected Scope(String request,String audio,long generation,boolean requestRender) {
            if(request==null || !request.matches("[A-Za-z0-9_-]{1,96}")
                    || (!requestRender && (audio==null || !audio.matches("[A-Za-z0-9_-]{1,96}"))) || generation<0)
                throw new IllegalArgumentException("reference_scope");
            this.request=request;this.audio=audio;this.generation=generation;this.requestRender=requestRender;
        }
    }
    public static final class Frame implements AutoCloseable {
        public final Scope scope;
        public final long firstFrame, presentationNanos;
        public final byte[] pcm;
        private Frame(Scope scope,long first,long ns,byte[] pcm) {
            this.scope=scope;firstFrame=first;presentationNanos=ns;this.pcm=pcm;
        }
        public void close(){Arrays.fill(pcm,(byte)0);}
    }
    private static final int RATE=24000, CHUNK=240, CAPACITY=48000;
    private static final long MAX_ANCHOR_AGE_NS=250000000L;
    private final Scope scope;
    private final byte[] ring=new byte[CAPACITY*2];
    private long accepted, read, anchorFrame=-1, anchorNanos=-1;
    private int route=-1;
    // DSP history only. Neither retention nor known digital silence is permission.
    private boolean retainCancellation, playbackCancelled, stopObserved, stopped;
    private boolean continuous, frozen, continuousClosed;
    private long captureEnd=-1, coveredEnd=-1;
    private long stopNanos=-1;
    private static final long MAX_STOP_HISTORY_NS=5000000000L;
    private Reason reason;
    public PlaybackReference(Scope scope,float speed) {
        this.scope=java.util.Objects.requireNonNull(scope);
        reason=speed==1f?Reason.WARMUP:Reason.SPEED_UNSUPPORTED;
    }
    public Scope scope(){return scope;}
    public synchronized Reason reason(){return reason;}
    public synchronized long acceptedFrames(){return accepted;}
    synchronized boolean tracking(){return active();}
    private boolean active(){return reason==Reason.WARMUP || reason==Reason.TRACKING_UNVERIFIED;}
    /** Called ONLY for the count returned by AudioTrack.write, never the requested count. */
    public synchronized void accepted(byte[] pcm,int offset,int count) {
        if(!active())return;
        if(playbackCancelled && !continuous){fail(Reason.WRITE_INVALID);return;}
        if(pcm==null || offset<0 || count<0 || offset>pcm.length-count || (count&1)!=0) {
            fail(Reason.WRITE_INVALID);return;
        }
        int frames=count/2;
        if(accepted-read+frames>CAPACITY){fail(Reason.OVERFLOW);return;}
        for(int i=0;i<count;i++)ring[(int)((accepted*2+i)%ring.length)]=pcm[offset+i];
        accepted+=frames;
    }
    /** MONOTONIC time, not elapsedRealtime/BOOTTIME. 32-bit wrap is intentionally
     * refused: one stream is bounded to 120s, far below a wrap at 24kHz. */
    public synchronized void presentation(long frame,long nanos,int deviceId,int deviceType,long now) {
        if(!active())return;
        // Built-in speaker is only a candidate route; even it is not acoustically verified.
        if(deviceType!=2 || deviceId<=0){fail(Reason.ROUTE_UNSUPPORTED);return;}
        if(route!=-1 && route!=deviceId){fail(Reason.ROUTE_CHANGED);return;}
        if(frame<0 || frame>accepted || nanos<=0 || now<nanos-MAX_ANCHOR_AGE_NS
                || now-nanos>MAX_ANCHOR_AGE_NS || frame<anchorFrame || nanos<anchorNanos
                || (frame==anchorFrame && anchorNanos>=0 && nanos!=anchorNanos)
                || (frame>anchorFrame && anchorFrame>=0 && nanos<=anchorNanos)) {
            fail(Reason.TIMESTAMP_INVALID);return;
        }
        if(anchorFrame>=0 && frame>anchorFrame) {
            long expected=(frame-anchorFrame)*1000000000L/RATE;
            // Reject clock discontinuity; this tolerance is NOT acoustic alignment proof.
            if(Math.abs((nanos-anchorNanos)-expected)>20000000L){fail(Reason.TIMESTAMP_INVALID);return;}
        }
        route=deviceId;anchorFrame=frame;anchorNanos=nanos;reason=Reason.TRACKING_UNVERIFIED;
    }
    public synchronized void timestampUnavailable(){if(anchorFrame>=0)fail(Reason.TIMESTAMP_LOST);}
    /** Complete 10ms frames: timestamp-covered PCM, then ONLY after verified cessation
     * bounded digital silence. Caller erases the private copy. No command permission. */
    public synchronized Frame take(Scope expected,long now) {
        if(expected!=scope || !active() || anchorFrame<0)return null;
        if(stopped) {
            // A bounded digital no-more-output interval, NOT an echo-tail timeout.
            if(now<stopNanos || now-stopNanos>MAX_STOP_HISTORY_NS){fail(Reason.TIMESTAMP_LOST);return null;}
            long end=anchorNanos+(read+CHUNK-anchorFrame)*1000000000L/RATE;
            if(end>now)return null;
        } else {
            if(now-anchorNanos>MAX_ANCHOR_AGE_NS){fail(Reason.TIMESTAMP_LOST);return null;}
            if(now<anchorNanos || read+CHUNK>anchorFrame)return null;
        }
        long first=read;
        byte[] pcm=new byte[CHUNK*2];
        for(int i=0;i<pcm.length;i++) {
            int slot=(int)((read*2+i)%ring.length);
            // A partial final frame preserves every observed accepted sample. Only
            // samples AFTER the confirmed cessation boundary are digital silence.
            if(read+i/2<accepted)pcm[i]=ring[slot];
            ring[slot]=0;
        }
        read+=CHUNK;
        return new Frame(scope,first,anchorNanos-(anchorFrame-first)*1000000000L/RATE,pcm);
    }
    /** Native processing exists, but residual speech/route acoustic qualification does not. */
    public boolean authorizesBargeIn(){return false;}
    synchronized boolean retainForCancellation(Scope expected) {
        if(expected!=scope || !active() || playbackCancelled)return false;
        retainCancellation=true;return true;
    }
    /** Immediate logical cancellation. Default cancellation still erases everything. */
    public synchronized void cancelPlayback() {
        playbackCancelled=true;
        if(!retainCancellation)revoke();
    }
    synchronized boolean retainingCancellation(){return active() && retainCancellation && playbackCancelled;}
    synchronized void stopUnproven(){if(active())fail(Reason.STOP_UNPROVEN);}
    /** Sink only, AFTER successful pause and BEFORE flush resets platform counters.
     * Must come from verifiedStopPresentation, never ordinary estimated metadata.
     * Every accepted sample must be covered; no drain or unknown-suffix extrapolation. */
    synchronized void observePaused(long frame,long nanos,int deviceId,int deviceType,long now) {
        if(!retainingCancellation())return;
        presentation(frame,nanos,deviceId,deviceType,now);
        if(!active())return;
        if(anchorFrame!=accepted || anchorNanos>now){fail(Reason.TIMESTAMP_INVALID);return;}
        stopObserved=true;stopNanos=anchorNanos;
    }
    /** Successful pause + flush + release are all mandatory. Blocked/failed native
     * cleanup never publishes silence. OFF can revoke concurrently and stays sticky. */
    synchronized void releasedAfterStop() {
        if(!active())return;
        if(!stopObserved || !retainCancellation || !playbackCancelled){revoke();return;}
        stopped=true;
    }
    synchronized boolean stoppedHistory(){return stopped && active();}
    /** Explicit same-track path. Never supplies unwritten silence or exact-stop claims. */
    synchronized boolean retainContinuous(Scope expected) {
        if(!retainForCancellation(expected))return false;
        continuous=true;playbackCancelled=true;return true;
    }
    synchronized void processedCapture(long end,long renderEnd) {
        if(active() && !frozen){captureEnd=end;coveredEnd=renderEnd;}
    }
    synchronized boolean freezeContinuous() {
        if(!continuous || !active() || captureEnd<0 || coveredEnd<captureEnd){revoke();return false;}
        frozen=true;return true;
    }
    synchronized void continuousReleased() {
        Arrays.fill(ring,(byte)0);
        if(!continuous || !frozen || !active()){revoke();return;}
        continuousClosed=true;
    }
    synchronized boolean continuousProof(){return continuous && frozen && continuousClosed && active();}
    synchronized boolean continuousRetained(){return continuous && active();}
    public synchronized void revoke(){fail(Reason.REVOKED);}
    private void fail(Reason failure){Arrays.fill(ring,(byte)0);reason=failure;}
}
