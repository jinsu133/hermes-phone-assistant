package com.mist.connect;

import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Paired normal VoiceLink transport. No trial arm, fake sink, approval RPC or retry.
 * Construct only from authenticated /status opt-in and the existing in-memory pairing.
 * Native-independent receipt model; the service supplies the real native start effect.
 */
public final class NormalVoiceStream implements SourceControllerTransport {
    private final SourceServicePlaybackAdapter owner;
    private final SourceLoopbackPlaybackTransport wire;
    private final String deviceEpoch;
    private volatile Connection connection;
    public NormalVoiceStream(int port,String pairedToken,String deviceEpoch,SourceServicePlaybackAdapter owner) {
        this.owner=owner;this.deviceEpoch=deviceEpoch;
        wire=new SourceLoopbackPlaybackTransport(port,pairedToken,owner);
    }
    private static String q(String s){return SourceControllerJson.quote(s);}
    private static String s(Map<String,Object> m,String k)throws IOException{return SourceControllerJson.string(m,k);}
    private static long n(Map<String,Object> m,String k)throws IOException{return SourceControllerJson.integer(m,k);}
    private static void status(Map<String,Object> m,String status)throws IOException {
        if(!status.equals(s(m,"status")))throw SourceControllerJson.bad();
    }
    private String scope(){return SourceLoopbackControllerTransport.scope(connection);}
    public Connection connect() throws IOException {
        Map<String,Object> m=SourceControllerJson.parse(wire.connect("{\"profile\":\"voicelink\",\"device_epoch\":"+q(deviceEpoch)+"}"));
        SourceControllerJson.keys(m,"profile","session","host_epoch","device_epoch","epoch","challenge","stopped_epoch");
        if(!"voicelink".equals(s(m,"profile")) || !deviceEpoch.equals(s(m,"device_epoch")))throw SourceControllerJson.bad();
        return connection=new Connection("voicelink",s(m,"session"),s(m,"host_epoch"),deviceEpoch,n(m,"epoch"),s(m,"challenge"),n(m,"stopped_epoch"));
    }
    public double heartbeat(Connection c)throws IOException {
        return SourceLoopbackControllerTransport.heartbeatResponse(wire.control("/heartbeat",SourceLoopbackControllerTransport.scope(c)+"}"));
    }
    public void stopAck(Connection c)throws IOException {
        Map<String,Object> m=SourceControllerJson.parse(wire.control("/stop-ack",SourceLoopbackControllerTransport.scope(c)
            +",\"challenge\":"+q(c.challenge)+",\"stopped_epoch\":"+c.stoppedEpoch+",\"closed\":true}"));
        SourceControllerJson.keys(m,"status");status(m,"stopped");
    }
    public void cancel(Connection c)throws IOException {
        Map<String,Object> m=SourceControllerJson.parse(wire.control("/cancel",SourceLoopbackControllerTransport.scope(c)+"}"));
        SourceControllerJson.keys(m,"status");status(m,"revoked");
    }
    public static final class Ticket {
        private final String full, correlation, request, audio, remote;
        private final Connection connection;
        public final long generation, consent;
        private boolean attempted;
        private Ticket(String full,String correlation,String request,String audio,String remote,Connection c,long generation,long consent) {
            this.full=full;this.correlation=correlation;this.request=request;this.audio=audio;this.remote=remote;
            this.connection=c;this.generation=generation;this.consent=consent;
        }
    }
    /** Immutable request authority, deliberately NOT an audio scope. */
    public static final class RenderScope extends PlaybackReference.Scope {
        final Connection controller;
        final ScopedVoiceCancellation.Scope cancellation;
        final long consent, deadline, voiceGeneration;
        final String ownerId, voiceSession, turnId;
        private RenderScope(Request r,String voiceSession,String turnId,long voiceGeneration){
            super(r.accepted.requestId,null,r.generation,true);
            controller=r.controller;cancellation=r.accepted;consent=r.consent;deadline=r.deadline;
            ownerId=r.ownerId;this.voiceSession=voiceSession;this.turnId=turnId;this.voiceGeneration=voiceGeneration;
        }
    }
    public static final class Request {
        final Connection controller;
        final ScopedVoiceCancellation.Scope accepted;
        final String ownerId=java.util.UUID.randomUUID().toString();
        final long generation,consent,deadline;
        final BooleanSupplier valid;
        final StreamingPcmPlayer.Clock clock;
        RenderScope scope;
        volatile SourcePcmPlaybackSession renderer;
        ContentDone pendingFinal;
        long lastWire=-1;
        int count;
        volatile boolean closeAttempted, closed, failed;
        // Admission/settlement transitions use NormalVoiceStream's monitor, never held for IO.
        boolean contentActive, descriptorAttempted, descriptorUnconfirmed, workerReserved, localOnlyClosed;
        private Request(Connection c,ScopedVoiceCancellation.Scope accepted,long generation,long consent,long deadline,
                BooleanSupplier valid,StreamingPcmPlayer.Clock clock){
            controller=c;this.accepted=accepted;this.generation=generation;this.consent=consent;
            this.deadline=deadline;this.valid=valid;this.clock=clock;
        }
    }
    /** Historical C completion only; no ticket, renderer-close assertion or listening authority. */
    public static final class ContentDone {
        final Request request;
        final SourcePcmPlaybackSession.Content content;
        final String full, correlation;
        final long frames;
        public final boolean finalContent;
        private ContentDone(Request r,SourcePcmPlaybackSession.Content c,String full,String correlation,long frames,boolean fin){
            request=r;content=c;this.full=full;this.correlation=correlation;this.frames=frames;finalContent=fin;
        }
    }
    private volatile Request requestRender;
    public Request beginRequest(ScopedVoiceCancellation.Scope accepted,long localGeneration,long consent,long deadline,
            BooleanSupplier valid,StreamingPcmPlayer.Clock clock)throws IOException {
        if(accepted==null || accepted.requestId==null || deadline<=clock.millis() || deadline-clock.millis()>540000
                || !valid.getAsBoolean() || !owner.controllerCurrent())throw new IOException("request_render_denied");
        synchronized(this){
            if(requestRender!=null && !requestRender.closed)throw new IOException("request_render_unclosed");
            if(!owner.beginPlaybackChecked(localGeneration,consent,valid))throw new IOException("request_render_owner");
            return requestRender=new Request(connection,accepted,localGeneration,consent,deadline,valid,clock);
        }
    }
    private boolean requestCurrent(Request r){return r!=null && requestRender==r && r.controller==connection && !r.failed
        && !r.closed && r.clock.millis()<r.deadline && r.valid.getAsBoolean() && owner.controllerCurrent();}
    private String renderFields(Request r){return ",\"render_owner_id\":"+q(r.ownerId)+",\"contract\":\"request-render-v1\"";}
    public ContentDone playContent(Request r,String audio,long wireGeneration,SourceServicePlaybackAdapter.Kind kind,
            BooleanSupplier wanted,Supplier<StreamingPcmPlayer.Sink> sinkFactory)throws IOException,InterruptedException {
        synchronized(this){
            if(!requestCurrent(r) || r.closeAttempted || r.contentActive || !wanted.getAsBoolean()
                    || kind==SourceServicePlaybackAdapter.Kind.APPROVAL_NOTICE)
                throw new IOException("request_render_unsupported_or_revoked");
            r.contentActive=true;
        }
        try{return playContentOwned(r,audio,wireGeneration,kind,wanted,sinkFactory);}
        finally{synchronized(this){r.contentActive=false;}}
    }
    private ContentDone playContentOwned(Request r,String audio,long wireGeneration,SourceServicePlaybackAdapter.Kind kind,
            BooleanSupplier wanted,Supplier<StreamingPcmPlayer.Sink> sinkFactory)throws IOException,InterruptedException {
        if(r.pendingFinal!=null || wireGeneration<=r.lastWire || r.count>=1024 ||
                (kind==SourceServicePlaybackAdapter.Kind.PROGRESS && r.count>=1023))throw new IOException("request_content_order");
        String name=kind==SourceServicePlaybackAdapter.Kind.PROGRESS?"progress":"final";
        String full=SourceLoopbackControllerTransport.scope(r.controller)+",\"request_id\":"+q(r.accepted.requestId)
            +",\"audio_id\":"+q(audio)+",\"generation\":"+wireGeneration;
        synchronized(this){
            r.lastWire=wireGeneration;r.count++;
            r.descriptorAttempted=true;r.descriptorUnconfirmed=true; // BEFORE wire dispatch
        }
        Map<String,Object> m;
        try {
        m=SourceControllerJson.parse(wire.control("/normal/v2/descriptor",full+renderFields(r)+",\"kind\":"+q(name)+"}"));
        SourceControllerJson.keys(m,"profile","session","host_epoch","device_epoch","epoch","request_id","audio_id","generation",
            "kind","render_owner_id","contract","correlation_id","voice_session","turn_id","voice_generation","text_sha256");
        Connection c=r.controller;
        if(!c.profile.equals(s(m,"profile")) || !c.session.equals(s(m,"session")) || !c.hostEpoch.equals(s(m,"host_epoch"))
            || !c.deviceEpoch.equals(s(m,"device_epoch")) || c.epoch!=n(m,"epoch") || wireGeneration!=n(m,"generation")
            || !r.accepted.requestId.equals(s(m,"request_id")) || !audio.equals(s(m,"audio_id")) || !name.equals(s(m,"kind"))
            || !r.ownerId.equals(s(m,"render_owner_id")) || !"request-render-v1".equals(s(m,"contract"))
            || s(m,"voice_session").isEmpty() || s(m,"turn_id").isEmpty() || n(m,"voice_generation")<0
            || !s(m,"text_sha256").matches("[a-f0-9]{64}") || !s(m,"correlation_id").matches("[a-f0-9]{32}"))
            throw SourceControllerJson.bad();
        if(r.scope==null)r.scope=new RenderScope(r,s(m,"voice_session"),s(m,"turn_id"),n(m,"voice_generation"));
        else if(!r.scope.voiceSession.equals(s(m,"voice_session")) || !r.scope.turnId.equals(s(m,"turn_id"))
                || r.scope.voiceGeneration!=n(m,"voice_generation"))throw SourceControllerJson.bad();
        synchronized(this){r.descriptorUnconfirmed=false;}
        if(!requestCurrent(r))throw new IOException("request_render_revoked");
        } catch(IOException failure){r.failed=true;throw failure;}
        String correlation=s(m,"correlation_id");owner.bindCorrelation(correlation);
        if(r.renderer==null){
            if(!owner.enqueueNormal(r.generation,r.ownerId,SourceServicePlaybackAdapter.Kind.FINAL,()->{
                r.renderer=new SourcePcmPlaybackSession(r.scope,sinkFactory.get(),r.clock);
                return r.renderer;
            }))throw new IOException("request_render_start");
            r.workerReserved=true; // reserved job, NOT renderer publication, owns allocation cleanup
            try {
                if(!owner.startNext())throw new IOException("request_render_start");
                long until=Math.min(r.deadline,r.clock.millis()+5000);
                while(r.renderer==null && requestCurrent(r) && r.clock.millis()<until)r.clock.pause();
                if(r.renderer==null)throw new IOException("request_render_allocation");
            } catch(IOException | InterruptedException failure){
                r.failed=true;owner.cancelRequestRender(r.generation,r.ownerId);throw failure;
            }
        }
        SourcePcmPlaybackSession.Content content=r.renderer.attach(audio,wireGeneration,kind,correlation,()->wire.stream(full+"}"));
        try {
            while(requestCurrent(r) && wanted.getAsBoolean() && !Thread.currentThread().isInterrupted()){
                if(r.renderer.awaitContent(content,50))break;
            }
            if(!requestCurrent(r) || !wanted.getAsBoolean() || !content.presented()){
                VoicePlaybackFailure failure=owner.playbackFailure();
                if(failure!=null)throw failure;
                if(content.failure!=null)throw VoicePlaybackFailure.safe(content.failure,VoicePlaybackFailure.Stage.NORMAL);
                throw new IOException("request_content_incomplete");
            }
            String payload=full+",\"correlation_id\":"+q(correlation)+renderFields(r)+",\"frame_count\":"+content.receipt().frameCount
                +",\"status\":\"content_presented\",\"cleanup_scope\":\"content\",\"renderer_closed\":false}";
            Map<String,Object> receipt=SourceControllerJson.parse(wire.control("/normal/v2/content-receipt",payload));
            Map<String,Object> expected=SourceControllerJson.parse(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));expected.put("status","content_accepted");
            if(!expected.equals(receipt))throw SourceControllerJson.bad();
            if(!requestCurrent(r) || !wanted.getAsBoolean())throw new IOException("request_content_revoked");
            ContentDone done=new ContentDone(r,content,full,correlation,content.receipt().frameCount,kind==SourceServicePlaybackAdapter.Kind.FINAL);
            r.renderer.acceptContent(content,done.finalContent);
            if(done.finalContent)r.pendingFinal=done;
            return done;
        } catch(IOException | InterruptedException failure){
            boolean interrupted=Thread.interrupted();
            try {
                r.renderer.cancelContent(content);
                Map<String,Object> result=SourceControllerJson.parse(wire.control("/normal/cancel",full+"}"));status(result,"cancelled");
                if(!r.renderer.awaitContent(content,5000) || !content.clean()) {r.failed=true;owner.failCleanup();}
                else r.renderer.discardContent(content);
                if(kind!=SourceServicePlaybackAdapter.Kind.PROGRESS){r.failed=true;owner.cancelUtterance();}
            } catch(Exception cleanup){r.failed=true;owner.failCleanup();failure.addSuppressed(VoicePlaybackFailure.safe(cleanup,VoicePlaybackFailure.Stage.SOURCE_CLOSE));}
            finally{if(interrupted)Thread.currentThread().interrupt();}
            throw failure;
        }
    }
    /** Called AFTER actual capture finally and request/progress/cancel joins. Never from the content caller. */
    public Ticket settle(Request r,ContentDone done)throws IOException,InterruptedException {
        synchronized(this){
            // Only a known clean local-only terminal is repeatable; never replay a network effect.
            if(r!=null && r.localOnlyClosed)return null;
            if(r==null || r!=requestRender || r.closeAttempted || r.contentActive)
                throw new IOException("request_close_attempted");
            r.closeAttempted=true;
        }
        try {
            // The reserved adapter job exists before the factory returns. Fence a late
            // result and join allocation/abort/source children even while renderer is null.
            boolean prepared=owner.prepareRequestRenderClose(r.generation,r.ownerId,r.scope,
                r.workerReserved && !r.failed && r.renderer!=null);
            boolean joined=owner.awaitWorker(5000);
            if(!prepared || !joined || !owner.requestRenderCleanupConfirmed(r.generation)
                    || (r.renderer!=null && (!r.renderer.sourceClosed() || !r.renderer.sinkClosed())))
                throw new IOException("request_render_cleanup_unconfirmed");
            if(r.descriptorUnconfirmed)throw new IOException("request_descriptor_unconfirmed");
            if(!r.descriptorAttempted){
                if(r.workerReserved || owner.blocked())throw new IOException("request_local_cleanup_unconfirmed");
                synchronized(this){r.localOnlyClosed=true;r.closed=true;}
                return null;
            }
        } catch(IOException | InterruptedException failure){
            r.failed=true;owner.failCleanup();throw failure; // sticky UNKNOWN, even after a late physical join
        }
        String payload=SourceLoopbackControllerTransport.scope(r.controller)+",\"request_id\":"+q(r.accepted.requestId)+renderFields(r)+",\"closed\":true}";
        Map<String,Object> closed=SourceControllerJson.parse(wire.control("/normal/v2/render-closed",payload));
        Map<String,Object> expected=SourceControllerJson.parse(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));expected.put("status","render_closed");
        if(!expected.equals(closed))throw SourceControllerJson.bad();
        if(done==null || done!=r.pendingFinal || done.request!=r || !requestCurrent(r) || !owner.played(r.generation)){
            r.closed=true;return null;
        }
        Map<String,Object> receipt=SourceControllerJson.parse(wire.control("/normal/receipt",done.full+",\"correlation_id\":"+q(done.correlation)
            +",\"frame_count\":"+done.frames+",\"status\":\"played\"}"));
        SourceControllerJson.keys(receipt,"status","correlation_id","listening_ticket");status(receipt,"accepted");
        if(!done.correlation.equals(s(receipt,"correlation_id")) || !s(receipt,"listening_ticket").matches("[a-f0-9]{32}"))throw SourceControllerJson.bad();
        r.closed=true;
        return new Ticket(done.full,done.correlation,r.accepted.requestId,done.content.audio,s(receipt,"listening_ticket"),r.controller,r.generation,r.consent);
    }
    /** Caller must serialize whole utterances and join progress before final/approval. */
    public Ticket play(String request,String audio,long generation,long consent,SourceServicePlaybackAdapter.Kind kind,
                       BooleanSupplier valid,Supplier<StreamingPcmPlayer.Sink> sinkFactory,StreamingPcmPlayer.Clock clock)
                       throws IOException,InterruptedException {
        if(!owner.controllerCurrent() || !valid.getAsBoolean())throw new IOException("normal_revoked");
        String name=kind==SourceServicePlaybackAdapter.Kind.PROGRESS?"progress":kind==SourceServicePlaybackAdapter.Kind.APPROVAL_NOTICE?"approval_notice":"final";
        Map<String,Object> m=SourceControllerJson.parse(wire.control("/normal/descriptor",scope()+",\"request_id\":"+q(request)
            +",\"audio_id\":"+q(audio)+",\"generation\":"+generation+",\"kind\":"+q(name)+"}"));
        SourceControllerJson.keys(m,"profile","session","host_epoch","device_epoch","epoch","request_id","audio_id","generation",
            "correlation_id","kind","voice_session","turn_id","voice_generation","text_sha256");
        Connection c=connection;
        if(!c.profile.equals(s(m,"profile")) || !c.session.equals(s(m,"session")) || !c.hostEpoch.equals(s(m,"host_epoch"))
            || !c.deviceEpoch.equals(s(m,"device_epoch")) || c.epoch!=n(m,"epoch") || generation!=n(m,"generation")
            || !request.equals(s(m,"request_id")) || !audio.equals(s(m,"audio_id")) || !name.equals(s(m,"kind"))
            || s(m,"voice_session").isEmpty() || s(m,"turn_id").isEmpty() || n(m,"voice_generation")<0
            || !s(m,"text_sha256").matches("[a-f0-9]{64}"))throw SourceControllerJson.bad();
        String correlation=s(m,"correlation_id");
        if(!correlation.matches("[a-f0-9]{32}"))throw SourceControllerJson.bad();
        String full=scope()+",\"request_id\":"+q(request)+",\"audio_id\":"+q(audio)+",\"generation\":"+generation;
        owner.bindCorrelation(correlation); // diagnostic binding only; never authority
        SourcePcmPlaybackSession[] session={null};
        boolean joined=false;
        Exception primary=null;
        try {
            if(!owner.beginPlaybackChecked(generation,consent,valid)
                || !owner.enqueueNormal(generation,audio,kind,()->{
                    session[0]=new SourcePcmPlaybackSession(request,audio,generation,()->wire.stream(full+"}"),sinkFactory.get(),clock);
                    session[0].diagnostics(owner::playbackMilestone);return session[0];
                }) || !owner.startNext())throw new VoicePlaybackFailure(VoicePlaybackFailure.Stage.NORMAL,VoicePlaybackFailure.Category.OWNER);
            long until=clock.millis()+300000;
            while(valid.getAsBoolean() && owner.controllerCurrent() && clock.millis()<until) {
                if(owner.retainingDuplex(generation))break;
                if(owner.awaitWorker(100)){joined=true;break;}
            }
            if(!joined || !valid.getAsBoolean() || !owner.played(generation) || session[0]==null
                || session[0].receipt()==null || !session[0].sourceClosed() || !session[0].sinkClosed()){
                VoicePlaybackFailure failure=owner.playbackFailure();
                throw failure!=null?failure:new VoicePlaybackFailure(VoicePlaybackFailure.Stage.NORMAL,VoicePlaybackFailure.Category.PLAYBACK);
            }
            Map<String,Object> receipt=SourceControllerJson.parse(wire.control("/normal/receipt",full+",\"correlation_id\":"+q(correlation)
                +",\"frame_count\":"+session[0].receipt().frameCount+",\"status\":\"played\"}"));
            SourceControllerJson.keys(receipt,"status","correlation_id","listening_ticket");status(receipt,"accepted");
            if(!correlation.equals(s(receipt,"correlation_id")))throw SourceControllerJson.bad();
            owner.playbackMilestone("receipt_accepted");
            if(kind==SourceServicePlaybackAdapter.Kind.PROGRESS) {
                if(!"".equals(s(receipt,"listening_ticket")))throw SourceControllerJson.bad();
                return null;
            }
            String ticket=s(receipt,"listening_ticket");
            if(!ticket.matches("[a-f0-9]{32}"))throw SourceControllerJson.bad();
            return new Ticket(full,correlation,request,audio,ticket,c,generation,consent);
        } catch(IOException | InterruptedException failure){primary=failure;throw failure;} finally {
            if(!joined || !valid.getAsBoolean()) {
                owner.cancelUtterance();
                boolean interrupted=Thread.interrupted();
                try {
                    // Exact captured host obligation must not queue behind source.close or
                    // the retained renderer. Capture finally, not this caller, ends maintenance.
                    Map<String,Object> result=SourceControllerJson.parse(wire.control("/normal/cancel",full+"}"));
                    status(result,"cancelled");
                    if(!owner.retainingDuplex(generation) && !owner.awaitWorker(5000))owner.failCleanup();
                } catch(IOException | InterruptedException cleanup){
                    owner.failCleanup();
                    VoicePlaybackFailure safe=VoicePlaybackFailure.safe(cleanup,VoicePlaybackFailure.Stage.SOURCE_CLOSE);
                    if(primary==null)throw safe;
                    if(primary.getSuppressed().length<3)primary.addSuppressed(safe);
                } finally {if(interrupted)Thread.currentThread().interrupt();}
            }
        }
    }
    /** Network validation is outside owner gate. A lost response consumes the attempt, never
     * retries/replays. Actual native start still checks lease, cleanup, consent and generation. */
    public boolean listen(Ticket ticket,String request,long consent,BooleanSupplier valid,SourceServicePlaybackAdapter.ListeningEffect effect)
            throws IOException {
        if(ticket==null)return false;
        synchronized(ticket) {
            if(ticket.attempted)return false;
            ticket.attempted=true;
        }
        if(ticket.connection!=connection || !ticket.request.equals(request) || ticket.consent!=consent
            || !valid.getAsBoolean() || !owner.played(ticket.generation))return false;
        Map<String,Object> m=SourceControllerJson.parse(wire.control("/normal/listening",ticket.full+",\"correlation_id\":"+q(ticket.correlation)
            +",\"listening_ticket\":"+q(ticket.remote)+"}"));
        SourceControllerJson.keys(m,"status","correlation_id");status(m,"consumed");
        if(!ticket.correlation.equals(s(m,"correlation_id")))throw SourceControllerJson.bad();
        return owner.consumeListening(ticket.generation,consent,
            ()->ticket.connection==connection && valid.getAsBoolean(),effect);
    }
}
