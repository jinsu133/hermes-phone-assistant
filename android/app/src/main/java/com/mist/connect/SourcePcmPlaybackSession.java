package com.mist.connect;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** HPS1 session for the shared service adapter. Transport must bound reads to <=5 seconds.
 * One sticky close attempt per resource; retries cannot erase uncertainty. */
public final class SourcePcmPlaybackSession implements SourceServicePlaybackAdapter.Session {
    private final StreamingPlaybackOwner.Source source;
    private final NormalVoiceStream.RenderScope renderScope;
    private final StreamingPcmPlayer.Clock renderClock;
    private volatile Content currentContent;
    private volatile boolean renderFinish, renderFinalAccepted, renderFailed, renderRetained;
    private long retainedStarted;
    private long lastContent=-1;
    private int contentCount;
    public static final class Content {
        public final String audio,correlation;
        public final long generation;
        public final SourceServicePlaybackAdapter.Kind kind;
        final StreamingPlaybackOwner.Source source;
        volatile StreamingPcmPlayer player;
        volatile StreamingPcmPlayer.Receipt receipt;
        volatile boolean done, clean, cancelled;
        volatile Throwable failure;
        private Content(String audio,long generation,SourceServicePlaybackAdapter.Kind kind,String correlation,StreamingPlaybackOwner.Source source){
            this.audio=audio;this.generation=generation;this.kind=kind;this.correlation=correlation;this.source=source;
        }
        public boolean clean(){return done && clean;}
        public boolean presented(){return clean() && !cancelled && failure==null && receipt!=null;}
        public StreamingPcmPlayer.Receipt receipt(){return receipt;}
    }
    /** Request mode reuses this session and the existing HPS1 player, never a second native writer. */
    SourcePcmPlaybackSession(NormalVoiceStream.RenderScope scope,StreamingPcmPlayer.Sink raw,StreamingPcmPlayer.Clock clock){
        renderScope=scope;renderClock=clock;source=null;player=null;sourceClosed=true;
        reference=raw.bindReference(scope);
        sink=raw;
        if(reference==null)throw new IllegalArgumentException("request_reference_required");
    }
    synchronized Content attach(String audio,long generation,SourceServicePlaybackAdapter.Kind kind,String correlation,StreamingPlaybackOwner.Source source){
        if(renderScope==null || cancelled || renderFinish || renderRetained || renderFailed || renderFinalAccepted || currentContent!=null
            || generation<=lastContent || contentCount>=1024)throw new IllegalStateException("request_content_busy");
        lastContent=generation;contentCount++;
        sourceClosed=false;
        return currentContent=new Content(audio,generation,kind,correlation,source);
    }
    boolean awaitContent(Content c,long millis)throws InterruptedException {
        long until=System.nanoTime()+millis*1000000L;
        synchronized(this){
            if(currentContent!=c)throw new IllegalStateException("stale_content_wait");
            while(!c.done && !renderFailed && System.nanoTime()<until)wait(Math.max(1,(until-System.nanoTime())/1000000L));
            return c.done;
        }
    }
    synchronized void acceptContent(Content c,boolean fin){
        if(currentContent!=c || !c.presented() || cancelled || renderFailed)throw new IllegalStateException("stale_content_accept");
        currentContent=null;renderFinalAccepted=fin;
    }
    synchronized void discardContent(Content c){
        if(currentContent!=c || !c.clean())throw new IllegalStateException("stale_content_discard");
        currentContent=null;
    }
    void cancelContent(Content c){
        synchronized(this){if(currentContent!=c)throw new IllegalStateException("stale_content_cancel");c.cancelled=true;}
        StreamingPcmPlayer p=c.player;if(p!=null)p.hardCancel();
    }
    public void finishRequestRender(){renderFinish=true;}
    private void renderCheck(){
        if(cancelled || renderClock.millis()>=renderScope.deadline)throw new IllegalStateException("request_render_revoked");
        if(renderRetained && renderClock.millis()-retainedStarted>=32000)throw new IllegalStateException("request_retention_expired");
    }
    private void idleRequest(){
        renderCheck();
        boolean retained=renderRetained;
        SourceServicePlaybackAdapter.StartGate selected=retained?maintenanceGate:gate;
        boolean admitted=selected!=null && selected.start(()->{if(renderRetained)sink.renderRetained();else sink.renderIdle();});
        // Selection precedes the owner lock. Retention may win while this renderer
        // waits in its ordinary gate; revalidate the SAME retained owner, never
        // reinterpret an OFF/poison rejection as permission or invent output.
        if(!admitted && !retained && renderRetained && maintenanceGate!=null)
            admitted=maintenanceGate.start(sink::renderRetained);
        if(!admitted)throw new IllegalStateException("request_render_owner_revoked");
        sink.updateReference();
    }
    private boolean playRequest()throws Exception {
        try {
            if(!gate.start(sink::start))throw new IllegalStateException("request_render_start_revoked");
            while(!renderFinish){
                renderCheck();
                Content c=currentContent;
                if(c==null || c.done || renderRetained){idleRequest();renderClock.pause();continue;}
                sink.beginContent();
                StreamingPcmPlayer.Sink span=new StreamingPcmPlayer.Sink(){
                    public void start(){} // physical Track already started once
                    public int write(byte[] b,int o,int n){
                        int[] written={-1};
                        if(!gate.start(()->{if(currentContent==c && !c.cancelled)written[0]=sink.write(b,o,n);}))return -1;
                        return written[0];
                    }
                    public long playedFrames(){return sink.playedFrames();}
                    public void updateReference(){sink.updateReference();}
                    public void renderIdle(){idleRequest();}
                    public boolean contentDrained(){return sink.contentDrained();}
                    public void abort(){} public void close(){} // content-only cleanup never closes R
                };
                StreamingPcmPlayer p=new StreamingPcmPlayer(renderScope.request,c.audio,c.generation,
                    (r,a,g)->currentContent==c && !c.cancelled && !cancelled,span,renderClock);
                c.player=p;if(c.cancelled)p.hardCancel();
                try {c.receipt=p.playContent(c.source,renderScope.deadline);}
                catch(Exception failure){c.failure=failure;if(!c.cancelled)throw failure;}
                finally {
                    c.clean=p.contentCleanupConfirmed();sourceClosed=c.clean;
                    synchronized(this){c.done=true;notifyAll();}
                    if(!c.clean){renderFailed=true;throw new IOException("request_content_cleanup_unconfirmed");}
                }
            }
            if(renderRetained)sink.finishRetained();
            return renderFinalAccepted && !cancelled && !renderFailed;
        } catch(Exception failure){renderFailed=true;throw failure;}
        finally {synchronized(this){notifyAll();}}
    }
    private final StreamingPcmPlayer player;
    private final StreamingPcmPlayer.Sink sink;
    private final PlaybackReference reference;
    private volatile boolean cancelled, sourceClosed, sinkClosed;
    private boolean sourceAttempted, sinkAttempted, openUncertain;
    private InputStream input;
    private StreamingPcmPlayer.Receipt receipt;
    private volatile SourceServicePlaybackAdapter.StartGate maintenanceGate;
    private volatile SourceServicePlaybackAdapter.StartGate gate=effect->{effect.start();return true;};

    public SourcePcmPlaybackSession(String request,String audio,long generation,
            StreamingPlaybackOwner.Source source,StreamingPcmPlayer.Sink rawSink,StreamingPcmPlayer.Clock clock) {
        this.source=source;renderScope=null;renderClock=null;
        reference=rawSink.bindReference(new PlaybackReference.Scope(request,audio,generation));
        sink=new StreamingPcmPlayer.Sink() {
            public void start(){if(!gate.start(rawSink::start))throw new IllegalStateException("owner_revoked");}
            public int write(byte[] b,int o,int n){
                final int[] result={-1};
                return gate.start(()->result[0]=rawSink.write(b,o,n))?result[0]:-1;
            }
            public boolean continuousEligible(){return rawSink.continuousEligible();}
            public void renderRetained(){
                if(maintenanceGate==null || !maintenanceGate.start(rawSink::renderRetained))throw new IllegalStateException("retained_revoked");
            }
            public void finishRetained(){rawSink.finishRetained();}
            public void updateReference(){rawSink.updateReference();}
            public long playedFrames(){return rawSink.playedFrames();}
            public void abort(){rawSink.abort();}
            public synchronized void close(){
                if(sinkAttempted)return;
                sinkAttempted=true;rawSink.close();sinkClosed=true;
            }
        };
        player=new StreamingPcmPlayer(request,audio,generation,(r,a,g)->!cancelled && gate.start(()->{}),sink,clock);
    }
    void diagnostics(java.util.function.Consumer<String> observer){if(player!=null)player.diagnostics(observer);}
    public boolean playGuarded(SourceServicePlaybackAdapter.StartGate gate) throws Exception {
        this.gate=java.util.Objects.requireNonNull(gate);return play();
    }
    public PlaybackReference playbackReference(){return reference;}
    public boolean retainContinuous(PlaybackReference.Scope scope,SourceServicePlaybackAdapter.StartGate gate){
        if(reference==null || !sink.continuousEligible() || !reference.retainContinuous(scope))return false;
        maintenanceGate=gate;
        if(renderScope!=null){
            retainedStarted=renderClock.millis();renderRetained=true;
            Content c=currentContent;if(c!=null)cancelContent(c);
            return true;
        }
        if(!player.retainContent()){reference.revoke();return false;}
        cancelled=true;return true;
    }
    public boolean retaining(){return renderScope!=null?renderRetained:player.retained();}
    public void endMaintenance(){if(renderScope!=null)renderFinish=true;else player.endMaintenance();}
    public void revokeStart(){
        cancelled=true;
        if(player!=null)player.hardCancel();
        Content c=currentContent;if(c!=null){c.cancelled=true;StreamingPcmPlayer p=c.player;if(p!=null)p.hardCancel();}
        if(reference!=null)reference.cancelPlayback();
    }
    public boolean play() throws Exception {
        if(renderScope!=null)return playRequest();
        if(cancelled){sourceClosed=true;return false;}
        // open failure may have uncertain transport ownership; conservative poison via false flag.
        openUncertain=true;
        try{input=java.util.Objects.requireNonNull(source.open());}
        catch(Exception failure){throw VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.TRANSPORT_OPEN);}
        openUncertain=false;
        receipt=player.playSplit(new FilterInputStream(input) {
            public void close() throws IOException {closeSource();}
        });
        return receipt!=null && !cancelled;
    }
    /** Logical only: the play worker owns every track operation, including hard cleanup.
     * A blocked open/read/close is never an excuse for a second native track caller. */
    public void abort(){revokeStart();}
    private void closeSource() throws IOException {
        final InputStream target;
        synchronized(this){if(sourceAttempted)return;sourceAttempted=true;target=input;}
        // No session/owner/queue monitor across arbitrary close/disconnect.
        if(target!=null)target.close();
        sourceClosed=!openUncertain;
    }
    public void close() throws Exception {
        if(renderScope!=null){
            if(!sinkAttempted){
                sinkAttempted=true;
                // Request abort is logical-only (also called by revocation workers).
                // This renderer-owned close must perform the physical retained stop
                // before release; close alone revokes even frozen continuous coverage.
                Throwable primary=null;
                if(renderRetained)try{sink.abort();}catch(Throwable e){primary=e;}
                try{sink.close();}catch(Throwable e){if(primary==null)primary=e;else if(primary!=e)primary.addSuppressed(e);}
                if(primary!=null){renderFailed=true;if(primary instanceof Error)throw (Error)primary;throw (Exception)primary;}
                sinkClosed=true;
            }
            return;
        }
        if(reference!=null && !player.retained())reference.cancelPlayback();
        VoicePlaybackFailure primary=null;
        try{closeSource();}catch(Exception failure){primary=VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.SOURCE_CLOSE);}
        try{sink.close();}catch(Exception failure){
            VoicePlaybackFailure safe=VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.SINK_CLOSE);
            if(primary==null)primary=safe;else if(primary!=safe)primary.addSuppressed(safe);
        }
        if(primary!=null){if(reference!=null)reference.revoke();throw primary;}
    }
    public boolean sourceClosed(){return renderScope!=null?sourceClosed:sourceClosed && player.cleanupConfirmed();}
    public boolean awaitSourceWorkers(long millis)throws InterruptedException{
        if(renderScope==null)return player.awaitSourceWorkers(millis);
        Content c=currentContent;return c==null || (c.player!=null && c.player.awaitSourceWorkers(millis));
    }
    public boolean sinkClosed(){return sinkClosed;}
    /** Historical receipt only. Adapter lifecycle must authorize any subsequent effect. */
    public StreamingPcmPlayer.Receipt receipt(){return receipt;}
}
