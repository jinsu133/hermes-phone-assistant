package com.mist.connect;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;

/** SOURCE ONLY: one coordinator for BOTH buffered MediaPlayer and streaming sessions.
 * VoiceRequestService retains one owner; NormalVoiceStream has a narrow lease-required admission.
 * Never replace an owner to clear poison.
 * Factories must be bounded/non-reentrant. Native abort/close may block: tracked teardown
 * runs outside the owner gate, and reuse waits for confirmed joins (no native hard timeout).
 * Sessions must close their source AND sink; a successful play is not cleanup evidence.
 */
public final class SourceServicePlaybackAdapter {
    public enum Kind { PROGRESS, FINAL, APPROVAL_NOTICE }
    public enum ServiceStage { CONTROLLER, REQUEST, PROGRESS, APPROVAL, HANDSFREE, CAPTURE, SYNTHETIC_TRIAL }
    /** Fixed vocabulary only. Never pass Throwable/message/remote identifiers to Android Log. */
    public void serviceFailure(ServiceStage stage, Throwable failure) {
        trace("service_"+Objects.requireNonNull(stage).name().toLowerCase(java.util.Locale.ROOT)+"_failed",failure);
    }
    public enum Mode { OFFLINE_FIXTURE, BUFFERED_SERVICE, LIVE_SERVICE }
    public interface Session {
        boolean play() throws Exception;
        default boolean playGuarded(StartGate gate) throws Exception {return play();}
        // Logical only: MUST NOT invoke/wait for native code or a session monitor.
        default void revokeStart() {}
        /** Logical immutable handle only; must not acquire a native/session monitor. */
        default PlaybackReference playbackReference(){return null;}
        default boolean retainContinuous(PlaybackReference.Scope scope,StartGate gate){return false;}
        default boolean retaining(){return false;}
        default void endMaintenance(){}
        void abort();
        void close() throws Exception;
        boolean sourceClosed();
        boolean sinkClosed();
        /** Physical child-thread join only; never clears sticky cleanup uncertainty. */
        default boolean awaitSourceWorkers(long millis) throws InterruptedException {return true;}
    }
    public interface StartGate { boolean start(ListeningEffect effect); }
    public interface Factory { Session create(); }
    public interface ListeningEffect { void start(); }
    public interface StopAckEffect { byte[] send() throws java.io.IOException; }
    private static final class Job {
        final String id; final long generation; final Kind kind; final Factory factory;
        final ServiceUiPublication publication; final ServiceUiPublication.Scope uiScope;
        Job(String id,long generation,Kind kind,Factory factory,ServiceUiPublication publication) {
            this.id=id;this.generation=generation;this.kind=kind;this.factory=factory;
            this.publication=publication;this.uiScope=publication==null?null:publication.requestScope();
        }
    }
    // Display sink is concrete, bounded memory publication; never a subscriber callback.
    private ServiceUiPublication uiPublication;
    private ServiceUiPublication.Scope uiSession;
    void bindUiPublication(ServiceUiPublication publication, ServiceUiPublication.Scope scope) {
        synchronized(lock){uiPublication=publication;uiSession=scope;}
    }
    static final class UiOwnedState {
        final boolean captureReserved, monitorReserved, cleanupUncertain, rendererJoined;
        UiOwnedState(boolean c,boolean m,boolean b,boolean j){captureReserved=c;monitorReserved=m;cleanupUncertain=b;rendererJoined=j;}
    }
    UiOwnedState readUiOwnedState() {
        synchronized(lock){return new UiOwnedState(listening,monitorActive,blocked,
            worker==null && abortWorker==null && session==null && progressWorker==null);}
    }
    private final Object lock=new Object();
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private final HashSet<String> ids=new HashSet<>();
    private long generation=-1, sequence=-1, consent=-1;
    private boolean enabled, destroyed, blocked, terminal, revoked;
    private PlaybackReference.Scope duplexScope;
    private PlaybackReference duplexReference;
    /** In-process one-attempt reservation, not approval or a listening capability. */
    public static final class UploadPermit {
        private final long generation, consent;
        private boolean used;
        private UploadPermit(long generation,long consent){this.generation=generation;this.consent=consent;}
    }
    private UploadPermit uploadPermit;
    public UploadPermit reserveUpload(long epoch,long revision,java.util.function.BooleanSupplier valid) {
        synchronized(lock){
            if(!controllerPermitted() || blocked || destroyed || epoch!=generation || revision!=consent
                    || !enabled || !valid.getAsBoolean())return null;
            return uploadPermit=new UploadPermit(epoch,revision);
        }
    }
    /** Immediately before WAV write. Only bounded ownership publication under this lock;
     * transport IO stays outside. OFF cancels the published exact ingress scope. */
    public boolean consumeUpload(UploadPermit permit,java.util.function.BooleanSupplier valid,ListeningEffect effect) {
        synchronized(lock){
            if(permit==null || permit!=uploadPermit || permit.used || !controllerPermitted() || !enabled || blocked || destroyed
                    || permit.generation!=generation || permit.consent!=consent || !valid.getAsBoolean())return false;
            permit.used=true;effect.start();return true;
        }
    }
    private Job running, completed;
    private Session session;
    private Thread worker, progressWorker, abortWorker;
    private static final java.util.concurrent.atomic.AtomicLong OWNER_IDS=new java.util.concurrent.atomic.AtomicLong();
    private final long ownerId=OWNER_IDS.incrementAndGet();
    private long requestLocal, audioLocal;
    private String trialCorrelation = "none";
    private VoicePlaybackFailure firstPlaybackFailure;
    VoicePlaybackFailure playbackFailure(){synchronized(lock){return firstPlaybackFailure;}}
    void playbackMilestone(String stage){
        if(stage.equals("sink_start") || stage.equals("sink_first_write") || stage.equals("pcm_eos") || stage.equals("sink_head_complete") || stage.equals("receipt_accepted"))trace(stage,null);
    }
    void trialCorrelation(String id) { bindCorrelation(id); }
    void bindCorrelation(String id) {
        if(id==null || !id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("correlation");
        synchronized(lock){trialCorrelation=id;firstPlaybackFailure=null;trace("descriptor_bound",null);}
    }
    private java.util.function.Consumer<String> traceSink = event -> {};
    public void requestStarted() {synchronized(lock){requestLocal++;trace("request_accepted",null);}}
    private final ArrayDeque<String> traceEvents = new ArrayDeque<>();
    public void setTraceSink(java.util.function.Consumer<String> sink) {synchronized(lock){traceSink=Objects.requireNonNull(sink);}}
    public String[] diagnosticSnapshot() {synchronized(lock){return traceEvents.toArray(new String[0]);}}
    private void trace(String stage, Throwable failure) {
        synchronized(lock) {
            String event="mono_ns="+System.nanoTime()+" owner_local="+ownerId+" request_local="+requestLocal
                +" audio_local="+audioLocal+" generation="+generation+" connection_local="+connectionLocal
                +" lease_elapsed_ms="+lease.clock.getAsLong()+" correlation_id="+trialCorrelation+" stage="+stage
                +" error="+(failure==null?"none":(failure instanceof SourceControllerTransport.Failure?((SourceControllerTransport.Failure)failure).code.name():failure instanceof java.io.IOException?"IOError":failure instanceof InterruptedException?"Interrupted":"OtherError"));
            if(failure instanceof VoicePlaybackFailure){
                VoicePlaybackFailure safe=(VoicePlaybackFailure)failure;
                event+=" "+safe.diagnostic();
                Throwable[] secondary=safe.getSuppressed();
                for(int i=0;i<Math.min(3,secondary.length);i++)if(secondary[i] instanceof VoicePlaybackFailure)
                    event+=" secondary_"+i+"="+((VoicePlaybackFailure)secondary[i]).stage.name()+":"+((VoicePlaybackFailure)secondary[i]).category.name();
            }
            if(traceEvents.size()==64)traceEvents.removeFirst();
            traceEvents.addLast(event);
            try{traceSink.accept(event);}catch(RuntimeException loggingFailure){
                if(traceEvents.size()==64)traceEvents.removeFirst();
                traceEvents.addLast("mono_ns="+System.nanoTime()+" stage=diagnostic_sink_failed");
            }
        }
    }
    /** Exact request job reservation also covers a factory whose Session is not published yet. */
    void cancelRequestRender(long epoch,String id){
        synchronized(lock){
            if(epoch==generation && (running==null || running.id.equals(id)))invalidate();
        }
    }
    boolean prepareRequestRenderClose(long epoch,String id,PlaybackReference.Scope expected,boolean finish){
        synchronized(lock){
            if(epoch!=generation || monitorActive || listening || progressWorker!=null
                    || (running!=null && !running.id.equals(id)))return false;
            if(session!=null && (expected==null || session.playbackReference()==null
                    || session.playbackReference().scope()!=expected))return false;
            if(finish && session!=null)session.endMaintenance();
            else invalidate(); // revoke the reserved job BEFORE a late factory can return/start
            return true;
        }
    }
    boolean requestRenderCleanupConfirmed(long epoch){
        synchronized(lock){return epoch==generation && !monitorActive && !listening && progressWorker==null
            && running==null && worker==null && abortWorker==null && session==null && queue.isEmpty();}
    }
    public boolean finishRequestRender(PlaybackReference.Scope expected){
        synchronized(lock){
            if(monitorActive || listening || progressWorker!=null || !expected.requestRender)return false;
            if(session==null)return running==null; // already terminated; caller still checks exact saved session and joins
            if(session.playbackReference()==null || session.playbackReference().scope()!=expected)return false;
            session.endMaintenance();return true;
        }
    }
    public void captureCleanup(CaptureCleanup.Outcome outcome) {captureCleanup(outcome,null);}
    public void captureCleanup(CaptureCleanup.Outcome outcome, Throwable primary) {
        synchronized(lock) {
            if(primary!=null)trace("capture_primary_failed",primary);
            trace("mic_stop",outcome.stopFailure);trace("aec_release",outcome.aecFailure);
            trace("mic_release",outcome.microphoneFailure);
            if(!outcome.confirmed()){listening=true;poison();}else monitorActive=false;
            if(primary!=null && session!=null && session.playbackReference()!=null
                    && session.playbackReference().scope().requestRender)poison();
            // Actual capture finally owns this edge; endpoint/host ACK alone cannot stop render.
            if(duplexReference!=null){
                if(!outcome.confirmed() || primary!=null || !duplexReference.freezeContinuous())poison();
                if(session!=null)session.endMaintenance();
            }
        }
    }
    private final ControllerLease lease;
    private ListeningEffect controllerRevoked=()->{};
    private long connectionLocal, connectionAttempt;
    long beginConnectionAttempt() {synchronized(lock){return ++connectionAttempt;}}
    boolean installController(SourceControllerTransport.Connection c,long budget,long attempt) {
        synchronized(lock){return attempt==connectionAttempt && installController(c,budget);}
    }
    /** Must be installed before a service begins. Callback is logical/atomic only. */
    public void requireController(ListeningEffect revokeConsent) {
        synchronized(lock){
            if(enabled || worker!=null || listening)throw new IllegalStateException("owner_active");
            lease.required=true;lease.revoke();controllerRevoked=Objects.requireNonNull(revokeConsent);
        }
    }
    public boolean installController(SourceControllerTransport.Connection c,long budget) {
        synchronized(lock){
            if(enabled || blocked || destroyed || listening || monitorActive || worker!=null || progressWorker!=null
                    || abortWorker!=null || running!=null || session!=null || lease.acknowledging)return false;
            // Fresh host session required, even after OFF/expiry. A heartbeat cannot substitute.
            if(lease.connection!=null && (c.session.equals(lease.connection.session)
                    || !c.deviceEpoch.equals(lease.connection.deviceEpoch)
                    || (c.hostEpoch.equals(lease.connection.hostEpoch) && c.epoch<=lease.connection.epoch)))return false;
            lease.install(c,budget);connectionLocal++;trace("controller_connected",null);return true;
        }
    }
    ControllerLease.Flight beginHeartbeat() {
        synchronized(lock){
            if(blocked || destroyed || !controllerAlive())return null;
            ControllerLease.Flight f=lease.begin();if(f!=null)trace("heartbeat_send",null);return f;
        }
    }
    boolean acceptHeartbeat(ControllerLease.Flight f,double seconds) {
        synchronized(lock){
            // Obsolete responses never revoke a newer connection.
            if(f==null || lease.connection!=f.connection || lease.flight!=f)return false;
            if(blocked || destroyed || !lease.accept(f,seconds)){trace("heartbeat_rejected",null);revokeController();return false;}
            trace("heartbeat_accepted",null);return true;
        }
    }
    public void controllerFailure(Throwable failure) {synchronized(lock){trace("controller_failure",failure);revokeController();}}
    public void revokeController() {
        synchronized(lock){
            boolean notify=lease.required && !lease.revoked;
            clearDuplex();connectionAttempt++;lease.revoke();enabled=false;revoked=true;queue.clear();completed=null;
            if(notify){controllerRevoked.start();trace("controller_revoked",null);}
            invalidate();interruptProgress();
        }
    }
    private boolean controllerAlive() {
        if(!lease.alive()){if(lease.required && !lease.revoked){trace("lease_expired",null);revokeController();}return false;}
        return true;
    }
    private boolean controllerPermitted() {
        if(!lease.required)return true;
        return controllerAlive() && lease.permitted();
    }
    public boolean controllerCurrent() {synchronized(lock){return !blocked && !destroyed && controllerPermitted();}}
    public boolean heartbeatDue() {synchronized(lock){return controllerAlive() && lease.clock.getAsLong()>=lease.renewAt;}}
    /** Network runs OUTSIDE the owner gate. The reservation blocks begin/install through ACK. */
    public boolean acknowledgeController(SourceControllerTransport transport) throws java.io.IOException {
        final SourceControllerTransport.Connection c;
        synchronized(lock){
            if(!controllerAlive() || !lease.renewed || lease.acknowledged || lease.acknowledging
                    || enabled || blocked || destroyed || listening || monitorActive || worker!=null || progressWorker!=null
                    || abortWorker!=null || running!=null || session!=null)return false;
            lease.acknowledging=true;c=lease.connection;trace("stop_ack_send",null);
        }
        boolean sent=false;
        try{transport.stopAck(c);sent=true;}
        finally{synchronized(lock){
            lease.acknowledging=false;
            if(!sent && lease.connection==c)revokeController();
        }}
        synchronized(lock){
            if(lease.connection!=c || !controllerAlive() || blocked || destroyed)return false;
            lease.acknowledged=true;trace("stop_ack_accepted",null);return true;
        }
    }
    private final Mode mode;
    private boolean listening, monitorActive, progressCancelSent;

    public SourceServicePlaybackAdapter(Mode mode) {this(mode,()->System.nanoTime()/1000000L);}
    public SourceServicePlaybackAdapter(Mode mode,java.util.function.LongSupplier elapsedRealtime) {
        this.lease=new ControllerLease(elapsedRealtime);
        this.mode=Objects.requireNonNull(mode);
        if(mode==Mode.LIVE_SERVICE)
            throw new IllegalStateException("live_blocked_provisioning_full_scope_ticket_and_lease_capability_required");
    }
    public boolean beginChecked(long nextGeneration,long consentRevision,java.util.function.BooleanSupplier valid) {
        synchronized(lock){return valid.getAsBoolean() && begin(nextGeneration,consentRevision);}
    }
    public void offAnd(ListeningEffect revokeConsent) {
        synchronized(lock) {
            // All logical state precedes scheduling native teardown under this same gate.
            enabled=false;revoked=true;completed=null;queue.clear();
            try{revokeConsent.start();}finally{trace("off_revoked",null);off();}
        }
    }
    /** Playback-only transition: keep the independent stop-monitor reservation intact.
     * This does not authorize capture, clear monitorActive or claim microphone cleanup.
     * The service's still-current consent/request predicate is evaluated under this gate. */
    public boolean beginPlaybackChecked(long nextGeneration,long consentRevision,java.util.function.BooleanSupplier valid) {
        synchronized(lock){return consentRevision==consent && valid.getAsBoolean()
            && begin(nextGeneration,consentRevision,true);}
    }
    public boolean begin(long nextGeneration,long consentRevision) {
        return begin(nextGeneration,consentRevision,false);
    }
    private boolean begin(long nextGeneration,long consentRevision,boolean playbackOnly) {
        synchronized(lock) {
            if(!controllerPermitted() || lease.acknowledging || blocked || destroyed || listening || (monitorActive && !playbackOnly) || abortWorker!=null || nextGeneration<=generation || consentRevision<0
                    || (mode==Mode.BUFFERED_SERVICE && (worker!=null
                        || (progressWorker!=null && progressWorker!=Thread.currentThread()))))return false;
            invalidate();
            if(blocked)return false;
            clearDuplex();generation=nextGeneration;consent=consentRevision;sequence=-1;
            terminal=false;ids.clear();enabled=true;return true;
        }
    }
    public boolean enqueue(long epoch,long seq,String id,Kind kind,Factory factory) {
        if(mode==Mode.BUFFERED_SERVICE)throw new IllegalStateException("buffered_factory_required");
        return enqueueOwned(epoch,seq,id,kind,factory);
    }
    // Only the final synthetic trial class calls this; no live factory/intent transport.
    boolean enqueueSyntheticTrial(long epoch,String id,SourcePcmPlaybackSession session) {
        return enqueueOwned(epoch,0,id,Kind.FINAL,()->session);
    }
    // Only the authenticated normal transport owns this admission; no LIVE fixture bypass.
    boolean enqueueNormal(long epoch,String id,Kind kind,Factory factory) {
        synchronized(lock){return lease.required && controllerPermitted() && enqueueOwned(epoch,0,id,kind,factory);}
    }
    public boolean enqueueBuffered(long epoch,long seq,String id,Kind kind,byte[] bytes) {
        return enqueueBuffered(epoch,seq,id,kind,bytes,SpeechPlaybackPolicy.DEFAULT_SPEED);
    }
    public boolean enqueueBuffered(long epoch,long seq,String id,Kind kind,byte[] bytes,float speed) {
        final float rate=SpeechPlaybackPolicy.requireSpeed(speed);
        return enqueueOwned(epoch,seq,id,kind,()->new SourceBufferedPlaybackSession(bytes,rate));
    }
    // Package-local deterministic backend injection: still the exact buffered Session implementation.
    boolean enqueueBuffered(long epoch,long seq,String id,Kind kind,byte[] bytes,SourceBufferedPlaybackSession.Backend backend) {
        return enqueueOwned(epoch,seq,id,kind,()->new SourceBufferedPlaybackSession(bytes,backend));
    }
    private boolean enqueueOwned(long epoch,long seq,String id,Kind kind,Factory factory) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,96}") || seq<0)throw new IllegalArgumentException("identity");
        Objects.requireNonNull(kind);Objects.requireNonNull(factory);
        synchronized(lock) {
            if(!controllerPermitted() || !enabled || blocked || destroyed || listening || epoch!=generation || seq<=sequence || terminal
                    || ids.contains(id) || ids.size()>=1024)return false;
            if(kind==Kind.PROGRESS && (queue.size()>=4 || ids.size()>=1023))return false;
            if(kind!=Kind.PROGRESS) {
                queue.clear();completed=null;revoke();
                if(blocked)return false;
                terminal=true;
            }
            ids.add(id);sequence=seq;queue.addLast(new Job(id,epoch,kind,factory,uiPublication));return true;
        }
    }
    /** Lazy allocation; caller pumps again after join for next queued descriptor. */
    public boolean startNext() {
        synchronized(lock) {
            if(!controllerPermitted() || !enabled || destroyed || blocked || listening || abortWorker!=null || queue.isEmpty() || (worker!=null && worker.isAlive()))return false;
            running=queue.removeFirst();revoked=false;audioLocal++;
            final Job job=running;
            worker=new Thread(()->run(job),"source-service-playback");
            worker.start();return true;
        }
    }
    private void run(Job job) {
        Session owned=null;boolean played=false, clean=false;
        try {
            synchronized(lock) {if(!current(job))return;}
            // Keep the worker reservation, but do not allocate an AudioTrack under the
            // owner gate. OFF may win during allocation; the late result still belongs
            // to this worker and must be closed/joined, never started or replaced.
            try {owned=Objects.requireNonNull(job.factory.create());}
            catch(RuntimeException failure){synchronized(lock){trace("factory_failed",failure);poison();}return;}
            synchronized(lock) {session=owned;if(!current(job))return;}
            played=owned.playGuarded(effect -> {
                synchronized(lock){if(!current(job))return false;effect.start();
                    // First successful native start, not enqueue/startNext/EOS. No audibility claim.
                    if(job.publication!=null && job.publication.read().playback!=ServiceUiPublication.Playback.ACTIVE)
                        job.publication.playback(job.uiScope,ServiceUiPublication.Playback.ACTIVE,ServiceUiPublication.Cleanup.PENDING);
                    return true;}
            });
        } catch(Throwable failure) {
            synchronized(lock){
                firstPlaybackFailure=VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.NORMAL);
                trace("play_failed",firstPlaybackFailure);
            }
        } finally {
            if(owned!=null) {
                boolean abort;
                synchronized(lock){abort=!played || !current(job);}
                // Never wait for a native/session monitor while holding the owner gate.
                if(abort)try {owned.abort();}catch(Throwable failure){synchronized(lock){trace("worker_abort_failed",VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.SINK_ABORT));poison();}}
                try {owned.close();clean=owned.sourceClosed() && owned.sinkClosed();}
                catch(Throwable failure){trace("playback_close_failed",VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.SOURCE_CLOSE));clean=false;}
            }
            synchronized(lock) {
                if(owned!=null && !clean)poison();
                if(played && clean && current(job))completed=job;
                running=null;if(clean)session=null; // retain uncertain teardown handles; poison is sticky
                if(job.publication!=null && owned!=null)job.publication.playback(job.uiScope,
                    clean?ServiceUiPublication.Playback.IDLE:ServiceUiPublication.Playback.UNKNOWN,
                    clean?ServiceUiPublication.Cleanup.PENDING:ServiceUiPublication.Cleanup.UNCERTAIN);
            }
        }
    }
    private boolean current(Job j) {return controllerPermitted() && enabled && !blocked && !destroyed && !revoked && running==j && generation==j.generation;}
    /** Legacy STOP has no residual qualification. Once this owner may have rendered,
     * it cannot authorize STOP from raw microphone text, including post-playback tails.
     * Widget OFF remains available; do not reset this latch on generation changes. */
    public boolean legacyStopAllowed() {
        synchronized(lock){return controllerPermitted() && enabled && !blocked && !destroyed
            && !revoked && audioLocal==0 && running==null && queue.isEmpty();}
    }
    /** Snapshot is diagnostic PCM metadata, never command/listening authority. */
    public PlaybackReference playbackReference(long epoch) {
        synchronized(lock){return running!=null && epoch==generation && current(running) && session!=null
            ? session.playbackReference():null;}
    }
    private void clearDuplex() {
        if(duplexReference!=null)duplexReference.revoke();
        duplexReference=null;duplexScope=null;
    }
    /** SAME monitor only; after logical cancellation the ordinary playback lookup is
     * intentionally null. Retained history does not revive playback/listening authority. */
    public PlaybackReference monitorReference(long epoch) {
        synchronized(lock){
            if(duplexReference!=null && monitorActive && epoch==generation
                    && duplexCurrent(duplexScope,consent,()->true))return duplexReference;
            return playbackReference(epoch);
        }
    }
    private void revokeReference() {
        if(session!=null){PlaybackReference ref=session.playbackReference();if(ref!=null)ref.revoke();}
    }
    private void poison() {if(uiPublication!=null)uiPublication.playback(uiSession,
        ServiceUiPublication.Playback.UNKNOWN,ServiceUiPublication.Cleanup.UNCERTAIN);uploadPermit=null;clearDuplex();revokeReference();blocked=true;enabled=false;queue.clear();completed=null;revoked=true;}
    private void revoke() {
        if(session!=null){
            PlaybackReference ref=session.playbackReference();
            if(ref!=null){if(ref==duplexReference)ref.cancelPlayback();else ref.revoke();}
        }
        if(running!=null) {
            revoked=true;
            if(session!=null && abortWorker==null) {
                final Session target=session;
                target.revokeStart();
                abortWorker=new Thread(()->{
                    trace("native_abort_begin",null);
                    try{target.abort();trace("native_abort_end",null);}
                    catch(Throwable failure){synchronized(lock){trace("native_abort_end",failure);poison();}}
                },"source-service-abort");
                try{abortWorker.start();}catch(Throwable failure){trace("abort_thread_failed",failure);poison();}
            }
        }
    }
    private void invalidate() {uploadPermit=null;enabled=false;completed=null;queue.clear();revoke();}
    /** Utterance/STOP invalidation preserves the authenticated widget controller lease. */
    public void cancelUtterance() {synchronized(lock){if(duplexScope==null)invalidate();}}
    public boolean retainingDuplex(long epoch){synchronized(lock){return duplexScope!=null && duplexScope.generation==epoch;}}
    public void cancelConversation(ListeningEffect invalidateRequest) {
        synchronized(lock){invalidate();interruptProgress();invalidateRequest.start();}
    }
    /** Join independent owners even after false/throw. Retain the first failure. */
    private boolean joinProgressAndRenderer(long millis) throws InterruptedException {
        Throwable first=null;
        boolean progress=false, renderer=false;
        try{progress=stopProgressAndJoin(millis);}catch(Throwable failure){first=failure;}
        // InterruptedException clears the interrupt bit; defer restoration/rethrow until
        // renderer/source cleanup has had its own bounded attempt.
        boolean interrupted=Thread.interrupted() || first instanceof InterruptedException;
        try{renderer=awaitWorker(millis);}catch(Throwable failure){
            if(first==null)first=failure;else if(first!=failure)first.addSuppressed(failure);
            interrupted|=failure instanceof InterruptedException;
        }
        // A bounded join timeout is not evidence of a failed cleanup: callers retry with a
        // longer deadline (OFF must not block on native teardown). Only a real throwable
        // means the cleanup path itself failed and must poison further effects.
        if(first!=null)failCleanup();
        if(interrupted)Thread.currentThread().interrupt();
        if(first instanceof InterruptedException)throw (InterruptedException)first;
        if(first instanceof RuntimeException)throw (RuntimeException)first;
        if(first instanceof Error)throw (Error)first;
        return progress && renderer;
    }
    public boolean cancelUtteranceAndJoin(long millis) throws InterruptedException {
        cancelUtterance();
        if(!joinProgressAndRenderer(millis))return false;
        // Monitor reservation survives STOP until its own cleanup.
        synchronized(lock){return !blocked && !listening && running==null && session==null;}
    }
    public void off() {synchronized(lock){clearDuplex();if(lease.required)revokeController();else {invalidate();interruptProgress();}}}
    private void interruptProgress() {
        if(progressWorker!=null && progressWorker!=Thread.currentThread() && !progressCancelSent) {
            progressCancelSent=true;progressWorker.interrupt();
        }
    }
    public void failCleanup() {synchronized(lock){poison();}}
    /** Registers before start, retains handle until joined; no detached progress producers. */
    public boolean startProgress(Runnable task) {
        synchronized(lock) {
            if(!controllerPermitted() || !enabled || blocked || destroyed || progressWorker!=null || listening)return false;
            progressCancelSent=false;progressWorker=new Thread(task,"voice-public-progress");progressWorker.start();return true;
        }
    }
    public boolean stopProgressAndJoin(long timeoutMillis) throws InterruptedException {
        if(timeoutMillis<1 || timeoutMillis>5000)throw new IllegalArgumentException("join_bound");
        final Thread old;
        synchronized(lock){old=progressWorker;interruptProgress();}
        if(old==Thread.currentThread())return false;
        try{if(old!=null)old.join(timeoutMillis);}
        catch(InterruptedException interrupted){synchronized(lock){poison();}throw interrupted;}
        synchronized(lock) {
            if((progressWorker!=old && progressWorker!=null) || (old!=null && old.isAlive())){poison();return false;}
            progressWorker=null;return !blocked;
        }
    }
    /** Start only; never hold this lock for microphone reads or network calls. */
    public boolean captureIdle(long epoch,long revision,java.util.function.BooleanSupplier valid,ListeningEffect effect) {
        synchronized(lock) {
            if(!controllerPermitted() || !enabled || blocked || destroyed || listening || monitorActive || epoch!=generation || revision!=consent
                    || worker!=null || progressWorker!=null || !queue.isEmpty() || !valid.getAsBoolean())return false;
            listening=true;
            try{effect.start();trace("capture_start",null);return true;}catch(RuntimeException failure){poison();return false;}
        }
    }
    public boolean consumeListening(long epoch,long revision,java.util.function.BooleanSupplier valid,ListeningEffect effect) {
        synchronized(lock) {
            if(listening || monitorActive || !valid.getAsBoolean())return false;
            return consumeListening(epoch,revision,()->{listening=true;effect.start();});
        }
    }
    public void endListening(long epoch) {endListening(epoch,true);}
    public void endListening(long epoch, boolean cleanupConfirmed) {
        synchronized(lock) {
            if(!cleanupConfirmed){listening=true;poison();}
            if(epoch==generation && cleanupConfirmed && !blocked)listening=false;
        }
    }
    /** Stop monitor is intentionally allowed alongside playback; OFF still excludes its start. */
    public boolean startMonitor(java.util.function.BooleanSupplier valid,ListeningEffect effect) {
        synchronized(lock) {
            if(!controllerPermitted() || !enabled || blocked || destroyed || listening || monitorActive || !valid.getAsBoolean())return false;
            try{monitorActive=true;effect.start();return true;}catch(RuntimeException failure){poison();return false;}
        }
    }
    /** Retain only this already-open monitor. No microphone start or acoustic authority. */
    public boolean retainDuplex(PlaybackReference.Scope scope,long revision,
            java.util.function.BooleanSupplier valid,ListeningEffect effect) {
        synchronized(lock){
            PlaybackReference ref=playbackReference(scope.generation);
            if(!enabled || !monitorActive || duplexScope!=null || ref==null || ref.scope()!=scope
                    || revision!=consent || !controllerPermitted() || blocked || destroyed || !valid.getAsBoolean())return false;
            if(session==null || !session.retainContinuous(scope,effectToRun->{
                synchronized(lock){if(!duplexCurrent(scope,revision,()->true))return false;effectToRun.start();return true;}
            }))return false;
            try {
                effect.start();duplexScope=scope;duplexReference=ref;
                // Revoke CONTENT, receipts and listening without aborting this renderer.
                enabled=false;completed=null;queue.clear();revoked=true;interruptProgress();
                return true;
            }
            catch(RuntimeException | Error failure){ref.revoke();poison();throw failure;}
        }
    }
    public boolean duplexCurrent(PlaybackReference.Scope scope,long revision,java.util.function.BooleanSupplier valid){
        synchronized(lock){return scope!=null && scope==duplexScope && scope.generation==generation && revision==consent
            && !blocked && !destroyed && controllerPermitted() && valid.getAsBoolean();}
    }
    /** Whole command transfer after real local joins; callback is bounded memory work only. */
    public boolean finishDuplex(PlaybackReference.Scope scope,long revision,
            java.util.function.BooleanSupplier valid,ListeningEffect effect){
        synchronized(lock){
            if(!duplexCurrent(scope,revision,valid) || monitorActive || listening || worker!=null
                    || progressWorker!=null || abortWorker!=null || session!=null
                    || duplexReference==null || !duplexReference.continuousProof())return false;
            try {effect.start();return true;} finally {clearDuplex();}
        }
    }
    public void destroy() {synchronized(lock){destroyed=true;off();}}
    public void consentChanged(long revision) {synchronized(lock){consent=revision;off();}}
    public boolean blocked() {synchronized(lock){return blocked;}}
    public boolean played(long epoch) {synchronized(lock){return controllerPermitted() && enabled && !blocked && !destroyed
        && completed!=null && completed.generation==epoch && worker==null;}}
    public boolean awaitWorker(long timeoutMillis) throws InterruptedException {
        if(timeoutMillis<1 || timeoutMillis>5000)throw new IllegalArgumentException("join_bound");
        final Thread old; synchronized(lock){old=worker;}
        if(old==Thread.currentThread())return false;
        long deadline=System.nanoTime()+timeoutMillis*1000000L;
        if(old!=null)old.join(timeoutMillis);
        final Thread abort;final Session pending;
        synchronized(lock){
            if((worker!=old && worker!=null) || (old!=null && old.isAlive())){
                if(!enabled || revoked || blocked)trace("join_unconfirmed",null);return false;
            }
            // A timeout/capture-finally poison can make the controller register abort
            // WHILE we join render. Snapshot it only after render physically exits:
            // run() has then cleared running, so no further abort for this job can start.
            abort=abortWorker;pending=session;
        }
        if(abort==Thread.currentThread())return false;
        long remaining=deadline-System.nanoTime();
        if(abort!=null && remaining>0)abort.join(Math.max(1,remaining/1000000L));
        remaining=deadline-System.nanoTime();
        // Renderer termination is not producer/closer termination. Keep every owner
        // handle on failure; a later physical join does NOT clear session uncertainty.
        boolean sources=pending==null || pending.awaitSourceWorkers(Math.max(0,remaining/1000000L));
        synchronized(lock){
            if(!sources || (worker!=old && worker!=null) || (old!=null && old.isAlive())
                || (session!=pending && session!=null)
                || (abortWorker!=abort && abortWorker!=null) || (abort!=null && abort.isAlive())) {
                if(!enabled || revoked || blocked)trace("join_unconfirmed",null);return false;
            }
            worker=null;abortWorker=null;trace("join_confirmed",null);
            if(uiPublication!=null)uiPublication.playback(uiSession,
                blocked?ServiceUiPublication.Playback.UNKNOWN:ServiceUiPublication.Playback.IDLE,
                blocked?ServiceUiPublication.Cleanup.UNCERTAIN:ServiceUiPublication.Cleanup.CONFIRMED);
            return true;
        }
    }
    /** Device stop evidence only after join AND confirmed source+sink close. OFF alone never ACKs.
     * Run on control IO, not main. Caller sends authenticated /stop-ack only on true.
     * A concurrent begin makes this false. No claim about another process's liveness. */
    public boolean stopAndJoin(long timeoutMillis) throws InterruptedException {
        off();
        if(!joinProgressAndRenderer(timeoutMillis))return false;
        synchronized(lock){return !enabled && !blocked && !listening && !monitorActive && running==null && session==null && queue.isEmpty();}
    }
    /** IO-only ACK boundary: prevents concurrent begin between joined cleanup and ACK.
     * Sender must be bounded and non-reentrant. Fixture control HTTP only; no distributed lock. */
    public byte[] stopAndAcknowledge(long timeoutMillis,StopAckEffect effect)
            throws InterruptedException,java.io.IOException {
        if(!stopAndJoin(timeoutMillis))throw new java.io.IOException("device_cleanup_unconfirmed");
        synchronized(lock) {
            if(enabled || blocked || listening || monitorActive || running!=null || session!=null
                    || worker!=null || progressWorker!=null || abortWorker!=null || lease.acknowledging)
                throw new java.io.IOException("device_cleanup_revoked");
            lease.acknowledging=true;
        }
        try{return effect.send();} // bounded transport IO outside owner gate
        finally{synchronized(lock){lease.acknowledging=false;}}
    }
    /** Local effect boundary, not a snapshot permission. Must execute a bounded synchronous
     * microphone-ownership transition, NOT post a later unchecked callback.
     * BUFFERED_SERVICE uses the existing buffered protocol only; this is not a streaming lease. */
    public boolean consumeListening(long epoch,long consentRevision,ListeningEffect effect) {
        Objects.requireNonNull(effect);
        synchronized(lock) {
            if(!controllerPermitted() || !enabled || blocked || destroyed || monitorActive || epoch!=generation || consentRevision!=consent
                    || completed==null || completed.kind==Kind.PROGRESS || running!=null || progressWorker!=null
                    || (worker!=null && worker.isAlive()))return false;
            completed=null;
            try {effect.start();trace("listening_start",null);return true;}
            catch(RuntimeException failure){poison();return false;}
        }
    }
}
