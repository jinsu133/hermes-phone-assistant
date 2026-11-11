// SPDX-License-Identifier: MIT
package com.mist.connect;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
/** Runs on the SAME thread that reads the one AudioRecord. No queue, second recorder,
 * network, STT, waits or native calls from cancellation. PCM never leaves this owner.
 * AEC/VAD produces unqualified candidates only; no acoustic acceptance exists yet. */
public final class DuplexCaptureWorker implements AutoCloseable {
    private final NativeDuplexApm apm;
    private final AtomicBoolean revoked=new AtomicBoolean();
    private PlaybackReference reference;
    private ContinuousCaptureHandoff handoff;
    private final short[] residual=new short[160],render=new short[240];
    private long next,firstNanos=-1,renderNext,renderNanos=-1,onset=-1;
    private int speechTicks;
    private boolean pinned,failed;
    private PlaybackReference.Frame pendingRender;
    private long waitingSince=-1;
    public DuplexCaptureWorker(){apm=new NativeDuplexApm();}
    // Pure-Java guard tests may pass null; never a public/native availability bypass.
    DuplexCaptureWorker(NativeDuplexApm apm){this.apm=apm;}
    public enum FailureReason { REVOKED, INVALID_PCM, CAPTURE_TIMESTAMP, CAPTURE_FUTURE, CAPTURE_AGE,
        CAPTURE_DISCONTINUITY, CAPTURE_TIMESTAMP_UNAVAILABLE, REFERENCE_MISSING, REFERENCE_IDENTITY, REFERENCE_UNTRACKED,
        RENDER_FRAME_GAP, RENDER_DISCONTINUITY, REFERENCE_WAIT, RENDER_FUTURE, RENDER_AGE,
        HANDOFF_APPEND, HANDOFF_PIN, CANDIDATE_REVOKED, CANDIDATE_REFUSED, NATIVE_PROCESS }
    public static final class FailureSnapshot {
        public final FailureReason reason;
        public final long nowNanos,captureNanos,captureAgeNanos,renderNanos,renderAgeNanos,captureFrames,renderFrames,referenceNanos;
        private FailureSnapshot(FailureReason reason,long now,long capture,long render,long frames,long reverse,long reference){
            this.reason=reason;nowNanos=now;captureNanos=capture;captureAgeNanos=now-capture;
            renderNanos=render;renderAgeNanos=render<0?-1:capture-render;captureFrames=frames;renderFrames=reverse;referenceNanos=reference;
        }
    }
    private volatile FailureSnapshot firstFailure;
    private long observedNow=-1,observedCapture=-1,observedReference=-1;
    public FailureSnapshot failureSnapshot(){return firstFailure;}
    /** Call on capture-thread exit if a native operation threw; cleanup never replaces first cause. */
    public void processingFailed(){fail(FailureReason.NATIVE_PROCESS);}
    /** Missing APK JNI means unavailable, never fake suppression or system-AEC fallback. */
    public static DuplexCaptureWorker openIfPackaged(){
        return openIfPackaged(error -> {});
    }
    public static DuplexCaptureWorker openIfPackaged(java.util.function.Consumer<Throwable> unavailable){
        try{return new DuplexCaptureWorker();}catch(UnsatisfiedLinkError absent){unavailable.accept(absent);return null;}
        catch(RuntimeException | Error failure){unavailable.accept(failure);throw failure;}
    }
    public void timestampUnavailable(){
        if(firstFailure==null)firstFailure=new FailureSnapshot(FailureReason.CAPTURE_TIMESTAMP_UNAVAILABLE,observedNow,observedCapture,renderNanos,next,renderNext,observedReference);
        revoke();
    }
    public void revoke(){revoked.set(true);} // nonblocking logical cancel; owner later joins/close
    public boolean acousticallyQualified(){return false;}
    public boolean failed(){return failed;}
    /** Same capture-thread call when the retained playback owner loses its reference. */
    public void referenceUnavailable(){if(reference!=null)fail(FailureReason.REFERENCE_MISSING);}
    private boolean fail(FailureReason reason){
        if(firstFailure==null)firstFailure=new FailureSnapshot(reason,observedNow,observedCapture,renderNanos,next,renderNext,observedReference);
        failed=true;if(handoff!=null)handoff.revoke();
        if(pendingRender!=null){pendingRender.close();pendingRender=null;}
        Arrays.fill(residual,(short)0);Arrays.fill(render,(short)0);return false;
    }
    /** Timestamp is actual capture-frame MONOTONIC time, NOT read-return wall time.
     * A new reference requires a fresh worker so old adaptive history cannot gain scope.
     * Max 25 reverse frames per tick bounds catchup to 250ms; no invented zero reference. */
    public interface QualifiedFrames {
        boolean candidate(PlaybackReference.Scope scope);
        boolean residual(short[] pcm);
        void endpoint(ContinuousCaptureHandoff.Command command);
    }
    public boolean accept(short[] pcm,long captureNanos,PlaybackReference ref,long now){
        return accept(pcm,captureNanos,ref,now,null);
    }
    private boolean notified;
    public boolean accept(short[] pcm,long captureNanos,PlaybackReference ref,long now,QualifiedFrames listener){
        observedNow=now;observedCapture=captureNanos;
        if(revoked.get()||failed)return fail(FailureReason.REVOKED);
        if(pcm==null||pcm.length!=160)return fail(FailureReason.INVALID_PCM);
        if(captureNanos<=0)return fail(FailureReason.CAPTURE_TIMESTAMP);
        if(now<captureNanos)return fail(FailureReason.CAPTURE_FUTURE);
        if(now-captureNanos>250000000L)return fail(FailureReason.CAPTURE_AGE);
        if(firstNanos>=0&&Math.abs(captureNanos-(firstNanos+next*62500L))>2000000L)return fail(FailureReason.CAPTURE_DISCONTINUITY);
        if(waitingSince<0)waitingSince=now;
        if(ref==null)return fail(FailureReason.REFERENCE_MISSING);
        if(reference==null){reference=ref;handoff=new ContinuousCaptureHandoff(ref.scope(),512000);}
        if(reference!=ref)return fail(FailureReason.REFERENCE_IDENTITY);
        if(!ref.tracking())return fail(FailureReason.REFERENCE_UNTRACKED);
        for(int i=0;i<25;i++){
            if(pendingRender==null)pendingRender=ref.take(ref.scope(),now);
            if(pendingRender==null||pendingRender.presentationNanos>captureNanos)break;
            PlaybackReference.Frame ready=pendingRender;pendingRender=null;
            try(PlaybackReference.Frame f=ready){
                observedReference=f.presentationNanos;
                if(f.firstFrame!=renderNext)return fail(FailureReason.RENDER_FRAME_GAP);
                if(renderNanos>=0&&Math.abs(f.presentationNanos-renderNanos-10000000L)>2000000L)return fail(FailureReason.RENDER_DISCONTINUITY);
                for(int j=0;j<240;j++)render[j]=(short)((f.pcm[j*2]&255)|(f.pcm[j*2+1]<<8));
                try{apm.render(render);}catch(RuntimeException | Error error){processingFailed();throw error;}finally{Arrays.fill(render,(short)0);}
                renderNext+=240;renderNanos=f.presentationNanos;
            }
        }
        // Reference/capture clock skew, no coverage and underrun fail closed, never reset to valid.
        if(!ref.tracking())return fail(FailureReason.REFERENCE_UNTRACKED); // stop evidence/OFF can fail during take()
        if(renderNanos<0){if(now-waitingSince>250000000L)return fail(FailureReason.REFERENCE_WAIT);return false;}
        if(captureNanos<renderNanos)return fail(FailureReason.RENDER_FUTURE);
        if(captureNanos-renderNanos>250000000L)return fail(FailureReason.RENDER_AGE);
        if(firstNanos<0)firstNanos=captureNanos;
        boolean speech;
        try{speech=apm.capture(pcm,residual,(int)((captureNanos-renderNanos)/1000000L));}
        catch(RuntimeException | Error error){processingFailed();throw error;}
        try {
            if(!handoff.append(ref.scope(),next,captureNanos,residual))return fail(FailureReason.HANDOFF_APPEND);
            if(!pinned){
                if(speech){if(speechTicks++==0)onset=next;}
                else {speechTicks=0;onset=-1;}
                // A request renderer outlives diagnostic VAD. Unqualified history must
                // roll, not pin and permanently overflow after 512000 samples.
                if(speechTicks>=3 && (!ref.scope().requestRender ||
                        (acousticallyQualified() && ref.authorizesBargeIn()))){
                    pinned=handoff.interrupt(ref.scope(),Math.max(0,onset-1600));if(!pinned)return fail(FailureReason.HANDOFF_PIN);
                }
            }
            if(revoked.get() || !ref.tracking())return fail(FailureReason.CANDIDATE_REVOKED);
            next+=160;
            ref.processedCapture(captureNanos+10000000L,renderNanos+10000000L);
            // Hard production gates, not a preference/Intent/test factory. Both remain false.
            if(listener!=null && pinned && acousticallyQualified() && ref.authorizesBargeIn()){
                if(!notified){if(!listener.candidate(ref.scope()))return fail(FailureReason.CANDIDATE_REFUSED);notified=true;}
                if(listener.residual(residual))listener.endpoint(candidate(true));
            }
            return speech; // residual VAD only; deliberately NOT NEAR_END or STOP
        } finally {Arrays.fill(residual,(short)0);}
    }
    /** Diagnostic whole-candidate extraction, not dispatch. Current-run clean settlement
     * and acoustic qualification remain independently required before any command use. */
    ContinuousCaptureHandoff.Command candidate(boolean wholeEndpoint){
        if(revoked.get()||failed||reference==null||!reference.tracking()){fail(FailureReason.CANDIDATE_REVOKED);return null;}
        if(reference.scope().requestRender && !pinned)return null;
        return handoff.finish(reference.scope(),next,wholeEndpoint);
    }
    public void close(){revoke();if(pendingRender!=null){pendingRender.close();pendingRender=null;}if(handoff!=null)handoff.revoke();Arrays.fill(residual,(short)0);Arrays.fill(render,(short)0);apm.close();}
}
