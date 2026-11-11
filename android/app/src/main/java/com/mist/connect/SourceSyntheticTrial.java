package com.mist.connect;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Init-only connected trial. FINAL, synthetic sink and counter ONLY, never microphone/approval.
 * Uses the actual retained service owner, controller driver and incremental HPS1 consumer.
 * A successful transport reply is historical: local completion/lease is checked at effect.
 */
public final class SourceSyntheticTrial implements SourceControllerTransport {
    private final SourceServicePlaybackAdapter owner;
    private final SourceLoopbackControllerTransport control;
    private final SourceLoopbackPlaybackTransport wire;
    private final LongSupplier elapsed;
    private final AtomicBoolean used=new AtomicBoolean();
    private Connection connection;
    private String full, correlation, request, audio, ticket;
    private long generation, revision;
    private boolean locallyConsumed;
    private volatile boolean stopped;
    public void stop(){stopped=true;owner.off();}
    private int effects;
    private final StreamingPcmPlayer.Sink deviceSink;
    public SourceSyntheticTrial(boolean explicitOptIn,int port,String syntheticCredential,
                                SourceServicePlaybackAdapter owner,LongSupplier elapsed) {
        this(explicitOptIn,port,syntheticCredential,owner,elapsed,null);
    }
    /** Init-only diagnostic sink; never obtained from Intent, pairing or production configuration. */
    public SourceSyntheticTrial(boolean explicitOptIn,int port,String syntheticCredential,
                                SourceServicePlaybackAdapter owner,LongSupplier elapsed,
                                StreamingPcmPlayer.Sink deviceSink) {
        if(!explicitOptIn)throw new IllegalArgumentException("synthetic_opt_in_required");
        this.deviceSink=deviceSink;
        this.owner=java.util.Objects.requireNonNull(owner);this.elapsed=java.util.Objects.requireNonNull(elapsed);
        control=new SourceLoopbackControllerTransport(true,port,syntheticCredential,"fixture","device-process-A",owner);
        wire=new SourceLoopbackPlaybackTransport(port,syntheticCredential,owner);
    }
    boolean owns(SourceServicePlaybackAdapter candidate){return owner==candidate;}
    public Connection connect() throws IOException {return connection=control.connect();}
    public double heartbeat(Connection c) throws IOException {return control.heartbeat(c);}
    public void stopAck(Connection c) throws IOException {control.stopAck(c);}
    public void cancel(Connection c) throws IOException {control.cancel(c);}
    private static String q(String s){return SourceControllerJson.quote(s);}
    private static String s(Map<String,Object> m,String k) throws IOException{return SourceControllerJson.string(m,k);}
    private void arm() throws IOException {
        Map<String,Object> m=SourceControllerJson.parse(wire.control("/trial/arm",SourceLoopbackControllerTransport.scope(connection)+"}"));
        SourceControllerJson.keys(m,"profile","session","host_epoch","device_epoch","epoch","request_id","audio_id","generation","correlation_id","consent_revision");
        if(!connection.profile.equals(s(m,"profile")) || !connection.session.equals(s(m,"session"))
                || !connection.hostEpoch.equals(s(m,"host_epoch")) || !connection.deviceEpoch.equals(s(m,"device_epoch"))
                || connection.epoch!=SourceControllerJson.integer(m,"epoch"))throw SourceControllerJson.bad();
        request=s(m,"request_id");audio=s(m,"audio_id");correlation=s(m,"correlation_id");
        generation=SourceControllerJson.integer(m,"generation");revision=SourceControllerJson.integer(m,"consent_revision");
        if(!request.matches("[A-Za-z0-9_-]{1,96}") || !audio.matches("[A-Za-z0-9_-]{1,96}")
                || !correlation.matches("[a-f0-9]{32}") || generation<0 || revision<1)throw SourceControllerJson.bad();
        full=SourceLoopbackControllerTransport.scope(connection)+",\"request_id\":"+q(request)+",\"audio_id\":"+q(audio)+",\"generation\":"+generation;
        owner.trialCorrelation(correlation);
    }
    private String scoped(){return full+",\"correlation_id\":"+q(correlation);}
    private Map<String,Object> exchange(String route,String body,String status,boolean receipt) throws IOException {
        Map<String,Object> m=SourceControllerJson.parse(wire.control(route,body));
        if(receipt)SourceControllerJson.keys(m,"status","correlation_id","listening_ticket");
        else SourceControllerJson.keys(m,"status","correlation_id");
        if(!status.equals(s(m,"status")) || !correlation.equals(s(m,"correlation_id")))throw SourceControllerJson.bad();
        return m;
    }
    // Only a synthetic counter can cross this gate. No user approval or arbitrary callback API.
    boolean localEffect(String candidate) {
        return owner.consumeListening(generation,revision,
            ()->!stopped && ticket!=null && ticket.equals(candidate) && !locallyConsumed,
            ()->{locallyConsumed=true;ticket=null;effects++;});
    }
    boolean hostConsume(String candidate) throws IOException {
        exchange("/trial/listening",scoped()+",\"listening_ticket\":"+q(candidate)
            +",\"consent_revision\":"+revision+"}","consumed",false);
        return true;
    }
    public static final class Result {
        public final String correlationId;
        public final long frames;
        public final int syntheticEffects;
        public final boolean replayDenied, offDenied;
        Result(String id,long frames,int effects,boolean replay,boolean off){
            correlationId=id;this.frames=frames;syntheticEffects=effects;replayDenied=replay;offDenied=off;
        }
    }
    public Result run() throws IOException,InterruptedException {
        if(stopped || !used.compareAndSet(false,true))throw new IOException("trial_one_use");
        owner.requireController(()->{});
        SourceControllerDriver driver=new SourceControllerDriver(owner,this,elapsed,3000);
        final CountDownLatch firstWrite=new CountDownLatch(1);
        final AtomicBoolean observed=new AtomicBoolean();
        Thread observer=null;
        Throwable primary=null;
        try {
            if(!driver.prepare())throw new IOException("trial_controller");
            arm();
            if(!owner.beginChecked(generation,revision,()->!stopped))throw new IOException("trial_owner");
            StreamingPcmPlayer.Sink sink=deviceSink==null ? new SyntheticSink(firstWrite) : new ObservedSink(deviceSink,firstWrite);
            SourcePcmPlaybackSession session=new SourcePcmPlaybackSession(request,audio,generation,
                ()->wire.stream(full+"}"),sink,new StreamingPcmPlayer.Clock(){
                    public long millis(){return elapsed.getAsLong();}
                    public void pause() throws InterruptedException{Thread.sleep(1);}
                });
            observer=new Thread(()->{
                try {
                    if(!firstWrite.await(3,TimeUnit.SECONDS) || !owner.controllerCurrent())throw new IOException("trial_first_chunk");
                    // controllerCurrent acquired/released owner gate AFTER sink.write; IO outside.
                    exchange("/trial/consumed",scoped()+"}","consumed",false);observed.set(true);
                }catch(IOException | InterruptedException failure){owner.controllerFailure(failure);}
            },"synthetic-consumption-observer");
            observer.start();
            if(!owner.enqueueSyntheticTrial(generation,audio,session) || !owner.startNext()
                    || !owner.awaitWorker(5000))throw new IOException("trial_playback");
            observer.join(3000);
            if(observer.isAlive() || !observed.get() || !owner.played(generation)
                    || session.receipt()==null || !session.sourceClosed() || !session.sinkClosed())throw new IOException("trial_cleanup");
            long frames=session.receipt().frameCount;
            Map<String,Object> accepted=exchange("/trial/receipt",scoped()+",\"frame_count\":"+frames+",\"status\":\"played\"}","accepted",true);
            ticket=s(accepted,"listening_ticket");
            if(!ticket.matches("[a-f0-9]{32}"))throw SourceControllerJson.bad();
            String historical=ticket;
            hostConsume(historical);
            if(!localEffect(historical))throw new IOException("trial_effect_revoked");
            owner.endListening(generation,true);
            boolean replay=false;
            try{hostConsume(historical);}catch(SourceControllerTransport.Failure failure){replay=failure.code==FailureCode.STALE;}
            if(!replay || localEffect(historical))throw new IOException("trial_replay");
            driver.stop();
            boolean playbackJoined=owner.stopAndJoin(3000);
            boolean controllerJoined=driver.stopAndJoin(3000);
            if(!playbackJoined || !controllerJoined)throw new IOException("trial_stop_join");
            boolean off=false;
            try{hostConsume(historical);}catch(SourceControllerTransport.Failure failure){off=failure.code==FailureCode.STALE;}
            if(!off || localEffect(historical))throw new IOException("trial_off");
            return new Result(correlation,frames,effects,replay,off);
        }catch(IOException | InterruptedException | RuntimeException failure){
            primary=failure;owner.controllerFailure(failure);throw failure;
        }finally{
            if(!cleanup(driver,observer) && primary==null)throw new IOException("trial_cleanup_unconfirmed");
        }
    }
    private boolean cleanup(SourceControllerDriver driver,Thread observer) {
        stopped=true;driver.stop();ticket=null;
        boolean clean=true,interrupted=Thread.interrupted();
        if(observer!=null){
            observer.interrupt();
            try{observer.join(3000);}catch(InterruptedException failure){interrupted=true;clean=false;}
            if(observer.isAlive())clean=false;
        }
        try{if(!owner.stopAndJoin(3000))clean=false;}
        catch(InterruptedException failure){interrupted=true;clean=false;}
        // Always join control separately, even if playback/observer cleanup failed.
        try{if(!driver.stopAndJoin(3000))clean=false;}
        catch(InterruptedException failure){interrupted=true;clean=false;}
        if(!clean)owner.failCleanup();
        if(interrupted)Thread.currentThread().interrupt();
        return clean;
    }
    private static final class ObservedSink implements StreamingPcmPlayer.Sink {
        private final StreamingPcmPlayer.Sink sink;
        private final CountDownLatch first;
        ObservedSink(StreamingPcmPlayer.Sink sink,CountDownLatch first){this.sink=sink;this.first=first;}
        public void start(){sink.start();}
        public int write(byte[] b,int o,int n){int written=sink.write(b,o,n);if(written>0)first.countDown();return written;}
        public long playedFrames(){return sink.playedFrames();}
        public void abort(){sink.abort();}
        public void close(){sink.close();}
    }
    private static final class SyntheticSink implements StreamingPcmPlayer.Sink {
        private final CountDownLatch first;
        private long frames;
        private boolean closed;
        SyntheticSink(CountDownLatch first){this.first=first;}
        public void start(){}
        public int write(byte[] b,int off,int n){if(closed)return -1;frames+=n/2;first.countDown();return n;}
        public long playedFrames(){return frames;} // explicitly FAKE playback head, not acoustic evidence
        public void abort(){closed=true;}
        public void close(){closed=true;}
    }
}
