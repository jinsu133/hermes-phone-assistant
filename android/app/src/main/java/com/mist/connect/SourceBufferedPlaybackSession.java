package com.mist.connect;

import android.media.AudioAttributes;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Buffered legacy-format session through the SAME SourceServicePlaybackAdapter.
 * No TTS fallback, files, Activity, preferences or separate player owner.
 * Input bytes transfer ownership to this session and are wiped on close.
 */
public final class SourceBufferedPlaybackSession implements SourceServicePlaybackAdapter.Session {
    public interface Backend {
        void prepare(byte[] bytes, Runnable completed, Runnable failed) throws Exception;
        void start();
        void abort();
        void close();
    }
    private final Backend backend;
    private final byte[] bytes;
    private final CountDownLatch done=new CountDownLatch(1);
    private volatile boolean cancelled;
    private boolean played, sourceClosed, sinkClosed, closeAttempted;
    public SourceBufferedPlaybackSession(byte[] bytes,Backend backend) {
        if(bytes==null || bytes.length==0 || bytes.length>VoiceBridgeProtocol.MAX_REPLY_AUDIO)
            throw new IllegalArgumentException("audio_size");
        this.bytes=bytes;this.backend=java.util.Objects.requireNonNull(backend);
    }
    public SourceBufferedPlaybackSession(byte[] bytes) {this(bytes,SpeechPlaybackPolicy.DEFAULT_SPEED);}
    public SourceBufferedPlaybackSession(byte[] bytes,float speed) {
        this(bytes,new AndroidBackend(SpeechPlaybackPolicy.requireSpeed(speed)));
    }
    public boolean play() throws Exception {return playGuarded(effect -> {effect.start();return true;});}
    public boolean playGuarded(SourceServicePlaybackAdapter.StartGate gate) throws Exception {
        // prepare may block: never hold lifecycle lock while preparing or waiting.
        backend.prepare(bytes,()->{synchronized(this){if(!cancelled)played=true;done.countDown();}},
                ()->done.countDown());
        if(!gate.start(()->{synchronized(this){if(!cancelled)backend.start();}}))return false;
        if(cancelled)return false;
        if(!done.await(300,TimeUnit.SECONDS))return false;
        synchronized(this){return played && !cancelled;}
    }
    public void revokeStart(){cancelled=true;done.countDown();}
    public synchronized void abort(){revokeStart();try{backend.abort();}finally{done.countDown();}}
    public synchronized void close() {
        if(closeAttempted)return;
        closeAttempted=true;
        try {backend.close();sinkClosed=true;}
        finally {Arrays.fill(bytes,(byte)0);sourceClosed=true;}
    }
    public synchronized boolean sourceClosed(){return sourceClosed;}
    public synchronized boolean sinkClosed(){return sinkClosed;}

    private static final class AndroidBackend implements Backend {
        private final float speed;
        AndroidBackend(float speed) {this.speed=speed;}
        private MediaPlayer player;
        private MediaDataSource data;
        private boolean aborted, prepared, closed;
        private RuntimeException cleanupFailure;
        public void prepare(byte[] bytes,Runnable complete,Runnable failed) throws Exception {
            final MediaPlayer local;
            synchronized(this) {
                if(aborted || closed)throw new IllegalStateException("cancelled");
                local=new MediaPlayer();player=local;
                data=new MediaDataSource() {
                    private boolean released;
                    public synchronized long getSize(){return released?-1:bytes.length;}
                    public synchronized int readAt(long pos,byte[] out,int offset,int count) {
                        if(released || pos<0 || pos>=bytes.length)return -1;
                        int n=Math.min(count,bytes.length-(int)pos);
                        System.arraycopy(bytes,(int)pos,out,offset,n);return n;
                    }
                    public synchronized void close(){released=true;}
                };
                local.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                local.setDataSource(data);
                local.setOnCompletionListener(p->complete.run());
                local.setOnErrorListener((p,w,e)->{failed.run();return true;});
            }
            local.prepare();
            synchronized(this){prepared=true;}
        }
        public synchronized void start(){
            if(aborted || closed)throw new IllegalStateException("cancelled");
            // Nonzero MediaPlayer params may START playback: keep inside the owner start gate.
            try {
                player.setPlaybackParams(new android.media.PlaybackParams().setSpeed(speed).setPitch(SpeechPlaybackPolicy.PITCH));
                player.start();
            } catch(RuntimeException failure) {
                aborted=true;
                try {close();} catch(RuntimeException cleanup) {if(cleanup!=failure)failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        public synchronized void abort(){
            if(aborted || closed)return;
            aborted=true;
            // prepare() has no started playback; worker must still join before replacement.
            if(prepared && player!=null)player.stop();
        }
        public synchronized void close(){
            if(closed){if(cleanupFailure!=null)throw cleanupFailure;return;}
            closed=true;
            RuntimeException failure=null;
            if(player!=null) {
                try{player.setOnCompletionListener(null);}catch(RuntimeException e){failure=e;}
                try{player.setOnErrorListener(null);}catch(RuntimeException e){failure=e;}
                try{player.release();}catch(RuntimeException e){failure=e;}
            }
            if(data!=null)try{data.close();}catch(java.io.IOException e){failure=new IllegalStateException("source_close",e);}
            if(failure!=null){cleanupFailure=failure;throw failure;}
        }
    }
}
