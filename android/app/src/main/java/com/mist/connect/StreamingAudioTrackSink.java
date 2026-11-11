package com.mist.connect;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Fresh AudioTrack per audio identity. PCM16LE, 24kHz mono; no MediaPlayer/WAV.
 * Internal track buffer is explicitly bounded to at most 48000 bytes (one second).
 * Playback head proves frames consumed by Android, NOT measured acoustic onset.
 * Instantiate/use/release on the playback worker; no microphone access.
 */
public final class StreamingAudioTrackSink implements StreamingPcmPlayer.Sink {
    /** JVM seam around the actual AudioTrack object, not a second sink algorithm. */
    public interface Track {
        void play(); int write(byte[] b,int o,int n); int head();
        void pause(); void flush(); void release();
        default Presentation presentation(){return null;}
        default int effectiveFrames(){return -1;}
        default int startThreshold(){return -1;}
        default int underruns(){return -1;}
        /** Stronger than AudioTimestamp: after pause, an exact END-exclusive presented
         * boundary with no committed/queued suffix and stable route. Android's public
         * timestamp is only presented OR committed; elapsed time/head/release cannot
         * upgrade it. The production AudioTrack adapter therefore returns null.
         * Offline Tracks can attest their completely observed synthetic render ledger. */
        default Presentation verifiedStopPresentation(){return null;}
    }
    public static final class Presentation {
        public final long frame,nanos;
        public final int deviceId,deviceType;
        public Presentation(long frame,long nanos,int id,int type){this.frame=frame;this.nanos=nanos;deviceId=id;deviceType=type;}
    }
    private final Track track;
    private final float speed;
    private PcmTempo tempo;
    private byte[] pending;
    private int pendingOffset, pendingSourceBytes;
    private long outputAccepted, sourceAccepted, sourcePlayed;
    private final java.util.ArrayDeque<long[]> mapping=new java.util.ArrayDeque<>();
    private PlaybackReference reference;
    private final java.util.function.LongSupplier monotonic;
    private boolean started;
    private long previous, wraps;
    private boolean closed, aborted, retainedRenderer, continuousCleanup;
    private volatile boolean continuousEligible;
    private int initialUnderruns=-1;
    private static final int OCCUPANCY_FRAMES=2400;
    private final byte[] zeros=new byte[480];
    public boolean continuousEligible(){return continuousEligible;}
    private long occupancy(){return outputAccepted-rawHead()+(pending==null?0:(pending.length-pendingOffset)/2);}
    private boolean policyValid(){
        try {
            int u=track.underruns();
            if(initialUnderruns<0)initialUnderruns=u;
            return track.effectiveFrames()>0 && track.effectiveFrames()<=OCCUPANCY_FRAMES
                && track.startThreshold()>0 && track.startThreshold()<=track.effectiveFrames()
                && u>=0 && u==initialUnderruns;
        } catch(RuntimeException unavailable){return false;} // ordinary playback is not duplex qualification
    }
    private void auditOccupancy(){
        long count=occupancy();
        if(!policyValid() || count<0 || count>OCCUPANCY_FRAMES){
            continuousEligible=false;
            if(retainedRenderer && reference!=null)reference.revoke();
        }
    }
    /** Renderer thread only; bounded accepted post-tempo tail, then real zero PCM. */
    public synchronized void renderRetained(){
        retainedRenderer=true;
        auditOccupancy();
        if(!continuousEligible || reference==null || !reference.continuousRetained())throw new IllegalStateException("continuous_render_invalid");
        byte[] bytes=pending==null?zeros:pending;
        int offset=pending==null?0:pendingOffset;
        int room=(int)Math.max(0,OCCUPANCY_FRAMES-(outputAccepted-rawHead()));
        int length=Math.min(bytes.length-offset,room*2);
        int n=length==0?0:track.write(bytes,offset,length);
        if(n<0 || n>length || (n&1)!=0){reference.revoke();throw new IllegalStateException("continuous_write");}
        reference.accepted(bytes,offset,n);outputAccepted+=n/2;
        if(pending!=null){
            pendingOffset+=n;
            if(pendingOffset==pending.length){java.util.Arrays.fill(pending,(byte)0);pending=null;}
        }
        // Maintenance never advances sourceAccepted or sourcePlayed receipts.
        // Timestamp polling stays outside the owner gate in the renderer loop.
    }
    public synchronized void finishRetained(){continuousCleanup=true;}
    /** Explicit request idle is reversible and does NOT cancel/retain the reference. */
    public synchronized void renderIdle(){
        if(!started || closed || aborted || reference==null || !reference.scope().requestRender)
            throw new IllegalStateException("request_idle_scope");
        auditOccupancy();
        if(!continuousEligible || !reference.tracking())throw new IllegalStateException("request_idle_invalid");
        byte[] bytes=pending==null?zeros:pending;
        int offset=pending==null?0:pendingOffset;
        int room=(int)Math.max(0,OCCUPANCY_FRAMES-(outputAccepted-rawHead()));
        int length=Math.min(bytes.length-offset,room*2);
        int n=length==0?0:track.write(bytes,offset,length);
        if(n<0 || n>length || (n&1)!=0){reference.revoke();throw new IllegalStateException("request_idle_write");}
        reference.accepted(bytes,offset,n);outputAccepted+=n/2;
        if(pending!=null){
            pendingOffset+=n;
            if(pendingOffset==pending.length){
                sourceAccepted+=pendingSourceBytes/2;
                mapping.addLast(new long[]{outputAccepted,sourceAccepted});
                java.util.Arrays.fill(pending,(byte)0);pending=null;
            }
        }
    }
    public synchronized boolean contentDrained(){retireMappings();return pending==null && mapping.isEmpty();}
    public synchronized void beginContent(){
        if(reference==null || !reference.scope().requestRender || tempo==null || !contentDrained())
            throw new IllegalStateException("request_content_boundary");
        tempo.close();tempo=new PcmTempo(speed);sourceAccepted=0;sourcePlayed=0;pendingSourceBytes=0;
    }

    public StreamingAudioTrackSink() {this(SpeechPlaybackPolicy.DEFAULT_SPEED);}
    public StreamingAudioTrackSink(float speed) {this(createTempoTrack(speed),speed,System::nanoTime,true);}
    public StreamingAudioTrackSink(Track track) {this(track,SpeechPlaybackPolicy.DEFAULT_SPEED,System::nanoTime);}
    StreamingAudioTrackSink(Track track,float speed,java.util.function.LongSupplier monotonic) {
        this(track,speed,monotonic,false);
    }
    StreamingAudioTrackSink(Track track,float speed,java.util.function.LongSupplier monotonic,boolean appTempo) {
        this.track=java.util.Objects.requireNonNull(track);this.speed=speed;this.monotonic=monotonic;
        tempo=appTempo?new PcmTempo(speed):null;
    }
    public synchronized PlaybackReference bindReference(PlaybackReference.Scope scope) {
        if(reference!=null || started || closed || aborted)throw new IllegalStateException("reference_already_bound");
        reference=new PlaybackReference(scope,tempo==null?speed:1f);return reference;
    }
    public synchronized void updateReference() {
        if(closed || aborted)return; // flush counters cannot rewrite retained history
        if(tempo!=null){auditOccupancy();retireMappings();}
        if(reference==null || !reference.tracking())return;
        // Reference failure never changes the user's configured playback or turns into admission.
        try {
            Presentation p=track.presentation();
            if(p==null)reference.timestampUnavailable();
            else reference.presentation(p.frame,p.nanos,p.deviceId,p.deviceType,monotonic.getAsLong());
        } catch(RuntimeException unavailable){reference.timestampUnavailable();}
    }
    private static Track createTempoTrack(float speed){SpeechPlaybackPolicy.requireSpeed(speed);return createTrack(1f);}
    private static Track createTrack(final float speed) {
        int minimum=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int buffer=Math.max(8192,minimum);
        if(minimum<=0 || buffer>48000) throw new IllegalStateException("unsupported_buffer");
        final AudioTrack track=new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build();
        if(track.getState()!=AudioTrack.STATE_INITIALIZED) {track.release();throw new IllegalStateException("track_init");}
        // Buffer tuning failure disables duplex eligibility, not ordinary playback.
        int configured;
        try { configured=track.setBufferSizeInFrames(OCCUPANCY_FRAMES); }
        catch(RuntimeException unsupported){configured=-1;}
        final int effectiveRequest=configured;
        // Allow even a short final utterance to start before its buffer fills.
        try {
            if(android.os.Build.VERSION.SDK_INT>=31) track.setStartThresholdInFrames(1);
        } catch(RuntimeException failure) {
            try {track.release();} catch(RuntimeException cleanup) {if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
        return new Track() {
            private boolean releaseAttempted, released;
            private RuntimeException cleanupFailure;
            public void play(){
                // Apply at the guarded start, after the owner has retained this sink.
                // Constructor failure would hide uncertain cleanup from that owner.
                try {
                    track.setPlaybackParams(new android.media.PlaybackParams().setSpeed(speed).setPitch(SpeechPlaybackPolicy.PITCH));
                    track.play();
                } catch(RuntimeException failure) {
                    try {release();} catch(RuntimeException cleanup) {if(cleanup!=failure)failure.addSuppressed(cleanup);}
                    throw failure;
                }
            }
            public int write(byte[] b,int o,int n){return track.write(b,o,n,AudioTrack.WRITE_NON_BLOCKING);}
            public int head(){return track.getPlaybackHeadPosition();}
            public int effectiveFrames(){int size=track.getBufferSizeInFrames();return effectiveRequest==size?size:-1;}
            public int startThreshold(){return android.os.Build.VERSION.SDK_INT>=31?track.getStartThresholdInFrames():-1;}
            public int underruns(){return track.getUnderrunCount();}
            public Presentation presentation(){
                android.media.AudioTimestamp timestamp=new android.media.AudioTimestamp();
                if(!track.getTimestamp(timestamp))return null;
                // Pre-36 cannot enumerate simultaneous routes: remain unsupported.
                if(android.os.Build.VERSION.SDK_INT<36)return new Presentation(timestamp.framePosition,timestamp.nanoTime,0,0);
                java.util.List<android.media.AudioDeviceInfo> routes=track.getRoutedDevices();
                if(routes.size()!=1)return new Presentation(timestamp.framePosition,timestamp.nanoTime,0,0);
                android.media.AudioDeviceInfo route=routes.get(0);
                return new Presentation(timestamp.framePosition,timestamp.nanoTime,route.getId(),route.getType());
            }
            public void pause(){if(!released)track.pause();}
            public void flush(){if(!released)track.flush();}
            public void release(){
                if(cleanupFailure!=null)throw cleanupFailure;
                if(releaseAttempted)return;
                releaseAttempted=true;
                try {track.release();released=true;}
                catch(RuntimeException failure){cleanupFailure=failure;throw failure;}
            }
        };
    }
    public synchronized void start() {
        if(closed || aborted) throw new IllegalStateException("inactive_track");
        try { started=true;continuousEligible=tempo!=null && policyValid();track.play(); }
        catch(RuntimeException failure) {
            if(reference!=null)reference.revoke();
            // Fixed-vocabulary marker: AudioTrack/PlaybackParams refusal stays visible
            // even when logcat is filtered or the process is later restarted.
            VoiceDiagnostics.INSTANCE.trace("sink_play_failed type="+failure.getClass().getSimpleName());
            throw failure;
        }
    }
    public synchronized int write(byte[] pcm,int offset,int length) {
        if(closed || aborted) return -1;
        try {
            if(tempo!=null)return writeTempo(pcm,offset,length);
            int written=track.write(pcm,offset,length);
            if(reference!=null) {
                if(written>length)reference.revoke();
                else reference.accepted(pcm,offset,written);
            }
            return written;
        }
        catch(RuntimeException failure) {
            if(reference!=null)reference.revoke();
            VoiceDiagnostics.INSTANCE.trace("sink_write_failed type="+failure.getClass().getSimpleName());
            throw failure;
        }
    }
    private int writeTempo(byte[] pcm,int offset,int length) {
        if(pcm==null||offset<0||length<=0||(length&1)!=0||offset>pcm.length-length)
            throw new IllegalArgumentException("tempo_write");
        if(pending==null){
            if(mapping.size()>=128)throw new IllegalStateException("tempo_mapping_bound");
            auditOccupancy();
            // Reserve for the ENTIRE transformed block before admitting any native prefix.
            int sourceBytes=Math.min(length,1920);
            long transformed=Math.round((tempo.sourceFrames()+sourceBytes/2)/(double)speed)-tempo.renderFrames();
            if(continuousEligible && occupancy()+transformed>OCCUPANCY_FRAMES)return 0;
            pendingSourceBytes=Math.min(length,1920);
            pending=tempo.transform(pcm,offset,pendingSourceBytes);pendingOffset=0;
        }
        // Contract: caller retries the same unconsumed source region on a zero return.
        if(length<pendingSourceBytes)throw new IllegalArgumentException("tempo_retry");
        int n=pending.length==0?0:track.write(pending,pendingOffset,pending.length-pendingOffset);
        if(n<0||n>pending.length-pendingOffset||(n&1)!=0)throw new IllegalStateException("tempo_native_write");
        if(reference!=null)reference.accepted(pending,pendingOffset,n); // EXACT post-DSP accepted bytes
        pendingOffset+=n;outputAccepted+=n/2;
        auditOccupancy();
        if(pendingOffset<pending.length)return 0;
        sourceAccepted+=pendingSourceBytes/2;
        mapping.addLast(new long[]{outputAccepted,sourceAccepted});
        java.util.Arrays.fill(pending,(byte)0);pending=null;
        return pendingSourceBytes;
    }
    private long rawHead(){
        long now=Integer.toUnsignedLong(track.head());
        if(now<previous)wraps+=1L<<32;
        previous=now;return wraps+now;
    }
    private void retireMappings(){
        long head=rawHead();
        if(head>outputAccepted)throw new IllegalStateException("tempo_head_invalid");
        while(!mapping.isEmpty()&&mapping.peekFirst()[0]<=head)sourcePlayed=mapping.removeFirst()[1];
    }
    private void eraseTempo(){
        if(pending!=null){java.util.Arrays.fill(pending,(byte)0);pending=null;}
        mapping.clear();if(tempo!=null)tempo.close();
    }
    public synchronized long playedFrames() {
        if(closed || aborted) return -1;
        updateReference();
        if(tempo!=null)return sourcePlayed;
        return rawHead();
    }
    public synchronized void abort() {
        if(continuousCleanup){
            if(!closed && !aborted){aborted=true;eraseTempo();try{track.pause();track.flush();}catch(RuntimeException | Error e){if(reference!=null)reference.revoke();throw e;}}
            return;
        }
        if(reference!=null)reference.cancelPlayback(); // logical fence before native calls
        if(!closed && !aborted) {
            aborted=true;eraseTempo();
            try {
                track.pause();
                if(reference!=null && reference.retainingCancellation()) {
                    Presentation p=track.verifiedStopPresentation();
                    if(p==null)reference.stopUnproven();
                    else reference.observePaused(p.frame,p.nanos,p.deviceId,p.deviceType,monotonic.getAsLong());
                }
                track.flush();
            } catch(RuntimeException | Error failure){if(reference!=null)reference.revoke();throw failure;}
        }
    }
    public synchronized void close() {
        if(!closed) {
            closed=true;eraseTempo();
            if(reference!=null && !aborted)reference.revoke();
            try {track.release();if(reference!=null){if(continuousCleanup)reference.continuousReleased();else if(aborted)reference.releasedAfterStop();}}
            catch(RuntimeException | Error failure){if(reference!=null)reference.revoke();throw failure;}
        }
    }
    public static StreamingPcmPlayer.Clock clock() {
        return new StreamingPcmPlayer.Clock() {
            public long millis() {return android.os.SystemClock.elapsedRealtime();}
            public void pause() throws InterruptedException {Thread.sleep(5);}
        };
    }
}
