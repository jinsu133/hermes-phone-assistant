package com.mist.connect;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-use bounded HPS1 consumer. Receipt requires head completion AND cleanup.
 * Ordinary play uses one block; the service split path uses one queued + one inflight
 * producer block and one renderer-owned block (8192 bytes each). Reads must be bounded. */
public final class StreamingPcmPlayer {
    public interface Sink extends AutoCloseable {
        void start(); int write(byte[] pcm,int offset,int length);
        long playedFrames(); void abort(); void close();
        /** Optional actual-sink reference; wrappers without one remain unsupported. */
        default PlaybackReference bindReference(PlaybackReference.Scope scope){return null;}
        /** Poll native timestamp/route outside the owner's guarded write effect. */
        default void updateReference(){}
        default boolean continuousEligible(){return false;}
        default void renderRetained(){throw new IllegalStateException("continuous_unsupported");}
        default void finishRetained(){}
        default void renderIdle(){throw new IllegalStateException("request_idle_unsupported");}
        default void beginContent(){throw new IllegalStateException("request_content_unsupported");}
        default boolean contentDrained(){return false;}
    }
    public interface Active { boolean matches(String request,String audio,long generation); }
    public interface Clock { long millis(); void pause() throws InterruptedException; }
    public static final class Receipt {
        public final String requestId,audioId;
        public final long generation,frameCount;
        private Receipt(String r,String a,long g,long f){requestId=r;audioId=a;generation=g;frameCount=f;}
    }
    private final String request,audio;
    private final long generation;
    private final Active active;
    private final Sink sink;
    private final Clock clock;
    private final AtomicBoolean used=new AtomicBoolean(),cancelled=new AtomicBoolean();
    private volatile InputStream input;
    private volatile boolean retained, maintenanceEnded, cleanupUncertain;
    private volatile long retainedAt;
    private volatile Split split;
    private boolean contentOnly;
    private long requestDeadline=Long.MAX_VALUE;
    private void idle() {if(contentOnly){sink.renderIdle();sink.updateReference();}}
    /** Offline scheduling observer only; invoked outside the queue/owner gates. */
    Runnable afterSplitPoll;
    /** Called under the owner gate: logical changes only, no IO/native/session waits. */
    boolean retainContent(){
        final Split state=split;
        if(!sink.continuousEligible() || state==null)return false;
        long now=clock.millis();
        // Owner -> state only. Never call a sink/owner predicate or clock under state.
        synchronized(state){
            if(state.terminal || cancelled.get())return false;
            retainedAt=now;retained=true;state.discard();return true;
        }
    }
    boolean retained(){return retained;}
    void endMaintenance(){maintenanceEnded=true;}
    void hardCancel(){cancelled.set(true);if(split!=null)split.discard();}
    boolean cleanupConfirmed(){return !cleanupUncertain;}
    boolean contentCleanupConfirmed(){
        Split state=split;
        return state!=null && !cleanupUncertain && state.closed && state.closeError==null
            && !state.producer.isAlive() && state.closer!=null && !state.closer.isAlive();
    }
    /** Physical completion only; sticky close/join deadlines stay unconfirmed. */
    boolean awaitSourceWorkers(long millis)throws InterruptedException{
        return split==null || split.joinThreads(millis);
    }

    private VoicePlaybackFailure.Stage stage=VoicePlaybackFailure.Stage.HEADER;
    private java.util.function.Consumer<String> diagnostic=event->{};
    void diagnostics(java.util.function.Consumer<String> observer){diagnostic=observer;}
    private void milestone(String event){try{diagnostic.accept(event);}catch(RuntimeException ignored){}}
    private VoicePlaybackFailure failure(VoicePlaybackFailure.Category c){return new VoicePlaybackFailure(stage,c);}
    public StreamingPcmPlayer(String request,String audio,long generation,Active active,Sink sink,Clock clock){
        if(!id(request)||!id(audio)||generation<0)throw new IllegalArgumentException("identity");
        this.request=request;this.audio=audio;this.generation=generation;this.active=active;this.sink=sink;this.clock=clock;
    }
    private static boolean id(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{1,96}");}
    public void cancel(){
        cancelled.set(true);
        try{sink.abort();}finally{InputStream current=input;if(current!=null)try{current.close();}catch(IOException ignored){}}
    }
    private void check(long deadline)throws IOException{
        if(cancelled.get()||(!active.matches(request,audio,generation)&&!retained))throw failure(VoicePlaybackFailure.Category.CANCELLED);
        if(clock.millis()>deadline)throw failure(VoicePlaybackFailure.Category.DEADLINE);
    }
    private String readId(DataInputStream in)throws IOException{
        int n=in.readUnsignedShort();
        if(n<1||n>96)throw failure(VoicePlaybackFailure.Category.IDENTITY);
        byte[] bytes=new byte[n];in.readFully(bytes);
        String s=new String(bytes,StandardCharsets.US_ASCII);
        if(!id(s))throw failure(VoicePlaybackFailure.Category.IDENTITY);
        return s;
    }
    /** One bounded producer (one queued + one inflight block) and this SINGLE renderer.
     * Source read/close never own the renderer or queue monitor. Retention drops all raw
     * content; only the sink's already-admitted transformed suffix can survive. */
    private final class Split {
        byte[] queued, inflight, rendering; int size;
        volatile boolean header, ended, closed;
        boolean eosValidated, terminal; // queue/producer completion and retention commit gate
        volatile long closeStarted;
        volatile long frames;
        volatile VoicePlaybackFailure error, closeError;
        Thread producer, closer;
        volatile InputStream opened;
        synchronized void discard(){if(queued!=null){java.util.Arrays.fill(queued,(byte)0);queued=null;}if(inflight!=null)java.util.Arrays.fill(inflight,(byte)0);if(rendering!=null)java.util.Arrays.fill(rendering,(byte)0);notifyAll();}
        synchronized void startClose(InputStream source){
            if(closer!=null)return;
            closeStarted=System.nanoTime();
            closer=new Thread(()->{
                try{source.close();closed=true;}catch(Exception e){closeError=VoicePlaybackFailure.safe(e,VoicePlaybackFailure.Stage.SOURCE_CLOSE);cleanupUncertain=true;}
            },"hps1-source-close");closer.start();
        }
        void openAndProduce(StreamingPlaybackOwner.Source opener,long deadline){
            try {opened=java.util.Objects.requireNonNull(opener.open());produce(opened,deadline);}
            catch(Throwable e){error=VoicePlaybackFailure.safe(e,VoicePlaybackFailure.Stage.TRANSPORT_OPEN);ended=true;cleanupUncertain=true;}
        }
        void produce(InputStream source,long deadline){
            byte[] block=null;boolean validated=false;
            try{
                InputStream checked=new FilterInputStream(source){
                    private void contentCheck()throws IOException{
                        if(retained)throw failure(VoicePlaybackFailure.Category.CANCELLED);
                        check(deadline);
                    }
                    public int read()throws IOException{contentCheck();int n=in.read();contentCheck();return n;}
                    public int read(byte[] b,int o,int n)throws IOException{contentCheck();int got=in.read(b,o,n);contentCheck();return got;}
                };
                DataInputStream in=new DataInputStream(checked);
                if(in.readInt()!=0x48505331||!request.equals(readId(in))||!audio.equals(readId(in))||generation!=in.readLong())
                    throw failure(VoicePlaybackFailure.Category.IDENTITY);
                header=true;
                while(!retained && !cancelled.get()){
                    check(deadline);
                    int kind=in.readUnsignedByte(),n=in.readInt();
                    if(kind==2){
                        if(n!=8||frames==0||in.readLong()!=frames||in.read()!=-1)throw failure(VoicePlaybackFailure.Category.EOS);
                        validated=true;milestone("pcm_eos");break;
                    }
                    if(kind!=1||n<=0||n>8192||(n&1)!=0)throw failure(VoicePlaybackFailure.Category.FRAME);
                    if(frames+n/2>2880000)throw failure(VoicePlaybackFailure.Category.PCM_LIMIT);
                    block=new byte[8192];synchronized(this){inflight=block;}in.readFully(block,0,n);
                    synchronized(this){
                        while(queued!=null&&!retained&&!cancelled.get())wait(10);
                        if(retained||cancelled.get())break;
                        queued=block;size=n;block=null;inflight=null;frames+=n/2;notifyAll();
                    }
                }
            }catch(Throwable e){if(!retained&&!cancelled.get())error=VoicePlaybackFailure.safe(e,VoicePlaybackFailure.Stage.FRAME);}
            finally{
                if(block!=null)java.util.Arrays.fill(block,(byte)0);
                synchronized(this){eosValidated=validated;ended=true;notifyAll();}
                startClose(source);
            }
        }
        boolean joinThreads(long millis)throws InterruptedException{
            long until=System.nanoTime()+millis*1000000L;
            // join(0) means infinite, not a poll. Exhausted budgets only inspect liveness.
            if(millis>0)producer.join(millis);
            Thread close; synchronized(this){close=closer;}
            long left=until-System.nanoTime();if(close!=null&&left>0)close.join(Math.max(1,left/1000000L));
            return !producer.isAlive()&&close!=null&&!close.isAlive();
        }
        boolean join(long millis)throws InterruptedException{
            boolean clean=joinThreads(millis)&&closed&&closeError==null;
            if(!clean)cleanupUncertain=true;
            return clean;
        }
    }
    Receipt playContent(StreamingPlaybackOwner.Source opener,long deadline)throws IOException,InterruptedException{
        contentOnly=true;requestDeadline=deadline;
        return playSplit(null,opener);
    }
    Receipt playSplit(InputStream source)throws IOException,InterruptedException{return playSplit(source,null);}
    private Receipt playSplit(InputStream source,StreamingPlaybackOwner.Source opener)throws IOException,InterruptedException{
        if(!used.compareAndSet(false,true))throw failure(VoicePlaybackFailure.Category.ALREADY_USED);
        input=source;split=new Split();final Split state=split;
        long deadline=Math.min(requestDeadline,clock.millis()+150000),frames=0;boolean completed=false,draining=false;
        VoicePlaybackFailure primary=null;byte[] block=null;
        state.producer=new Thread(()->{if(opener==null)state.produce(source,deadline);else state.openAndProduce(opener,deadline);},"hps1-producer");state.producer.start();
        try{
            while(!state.header){if(state.ended){if(state.error!=null)throw state.error;throw failure(VoicePlaybackFailure.Category.CANCELLED);}check(deadline);idle();clock.pause();}
            check(deadline);stage=VoicePlaybackFailure.Stage.SINK_START;sink.start();milestone("sink_start");
            while(true){
                if(retained){
                    state.discard();state.startClose(source);
                    if(block!=null){java.util.Arrays.fill(block,(byte)0);block=null;}
                    if(cancelled.get())throw failure(VoicePlaybackFailure.Category.CANCELLED);
                    if(state.error!=null)throw state.error;
                    if(state.closeError!=null){cleanupUncertain=true;throw state.closeError;}
                    if(!state.closed && System.nanoTime()-state.closeStarted>=5000000000L){
                        cleanupUncertain=true;throw new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SOURCE_CLOSE,VoicePlaybackFailure.Category.DEADLINE);
                    }
                    if(maintenanceEnded){sink.finishRetained();break;}
                    if(clock.millis()-retainedAt>=32000)throw failure(VoicePlaybackFailure.Category.DEADLINE);
                    sink.renderRetained();sink.updateReference();clock.pause();continue;
                }
                if(draining){
                    stage=VoicePlaybackFailure.Stage.SINK_HEAD;
                    check(deadline);long played=sink.playedFrames();
                    if(played<0||played>frames)throw failure(VoicePlaybackFailure.Category.SINK_HEAD);
                    if(played==frames){
                        check(deadline);
                        synchronized(state){
                            if(retained)continue; // accepted retention wins even at the final head
                            if(cancelled.get())throw failure(VoicePlaybackFailure.Category.CANCELLED);
                            state.terminal=true;completed=true;
                        }
                        milestone("sink_head_complete");break;
                    }
                    idle();clock.pause();continue; // post-EOS retention enters the SAME maintenance loop
                }
                check(deadline);if(frames>0)sink.updateReference();
                int size=0;boolean exhausted;
                synchronized(state){
                    if(state.queued!=null){block=state.queued;state.rendering=block;size=state.size;state.queued=null;state.notifyAll();}
                    // Empty poll and producer-ended must be one observation, not two epochs.
                    exhausted=block==null&&state.ended;
                }
                if(afterSplitPoll!=null)afterSplitPoll.run();
                if(block==null){
                    if(retained)continue;
                    if(exhausted){
                        if(state.error!=null)throw state.error;
                        if(!state.eosValidated||frames!=state.frames)throw failure(VoicePlaybackFailure.Category.EOS);
                        draining=true;continue;
                    }
                    idle();clock.pause();continue;
                }
                int offset=0;stage=VoicePlaybackFailure.Stage.SINK_WRITE;
                while(offset<size&&!retained){
                    check(deadline);int n=sink.write(block,offset,size-offset);
                    // Logical retention may race the owner-gated write; do not turn that
                    // rejected raw write into a hard abort of the admitted DSP suffix.
                    if(retained)break;
                    if(n<0||n>size-offset||(n&1)!=0)throw failure(VoicePlaybackFailure.Category.SINK_WRITE);
                    sink.updateReference();
                    if(n==0)clock.pause();else{if(frames==0&&offset==0)milestone("sink_first_write");offset+=n;}
                }
                if(!retained)frames+=size/2;
                java.util.Arrays.fill(block,(byte)0);block=null;state.rendering=null;
            }
        }catch(Exception e){primary=VoicePlaybackFailure.safe(e,stage);}
        finally{
            if(block!=null)java.util.Arrays.fill(block,(byte)0);
            synchronized(state){state.terminal=true;cancelled.set(true);state.discard();}
            if(!contentOnly)state.startClose(source);
            else if(state.opened!=null)state.startClose(state.opened);
            if(!contentOnly){
                if(!completed)try{sink.abort();}catch(Exception e){primary=append(primary,e,VoicePlaybackFailure.Stage.SINK_ABORT);}
                try{sink.close();}catch(Exception e){primary=append(primary,e,VoicePlaybackFailure.Stage.SINK_CLOSE);}
            }
            try{
                if(contentOnly){
                    long until=System.nanoTime()+5000000000L;
                    // Sole renderer stays live during blocked open/read/close. Cleanup timeout
                    // is sticky and the child handles remain owned, never detached/replaced.
                    while((!state.joinThreads(0) || !sink.contentDrained()) && System.nanoTime()<until && clock.millis()<requestDeadline){
                        if(state.opened!=null)state.startClose(state.opened);
                        idle();clock.pause();
                    }
                }
                if(!state.join(contentOnly?1:5000))primary=append(primary,new IOException("source_cleanup_unconfirmed"),VoicePlaybackFailure.Stage.SOURCE_CLOSE);
            }
            catch(InterruptedException e){cleanupUncertain=true;primary=append(primary,e,VoicePlaybackFailure.Stage.SOURCE_CLOSE);Thread.currentThread().interrupt();}
            input=null;
        }
        if(primary!=null)throw primary;
        return completed?new Receipt(request,audio,generation,frames):null;
    }
    public Receipt play(InputStream source)throws IOException,InterruptedException{
        if(!used.compareAndSet(false,true))throw failure(VoicePlaybackFailure.Category.ALREADY_USED);
        input=source;
        VoicePlaybackFailure primary=null;
        boolean interrupted=false,completed=false;
        long deadline=clock.millis()+150000,frames=0;
        InputStream checked=new FilterInputStream(source){
            public int read()throws IOException{check(deadline);int n=in.read();check(deadline);return n;}
            public int read(byte[] b,int o,int n)throws IOException{check(deadline);int got=in.read(b,o,n);check(deadline);return got;}
        };
        DataInputStream in=new DataInputStream(checked);
        try{
            check(deadline);
            if(in.readInt()!=0x48505331||!request.equals(readId(in))||!audio.equals(readId(in))||generation!=in.readLong())
                throw failure(VoicePlaybackFailure.Category.IDENTITY);
            check(deadline);stage=VoicePlaybackFailure.Stage.SINK_START;sink.start();milestone("sink_start");
            byte[] block=new byte[8192];
            while(true){
                stage=VoicePlaybackFailure.Stage.FRAME;check(deadline);
                int kind=in.readUnsignedByte(),size=in.readInt();
                if(kind==2){
                    stage=VoicePlaybackFailure.Stage.EOS;
                    if(size!=8||frames==0||in.readLong()!=frames)throw failure(VoicePlaybackFailure.Category.EOS);
                    if(in.read()!=-1)throw failure(VoicePlaybackFailure.Category.EOS);
                    milestone("pcm_eos");break;
                }
                if(kind!=1||size<=0||size>block.length||(size&1)!=0)throw failure(VoicePlaybackFailure.Category.FRAME);
                if(frames+size/2>2880000)throw failure(VoicePlaybackFailure.Category.PCM_LIMIT);
                in.readFully(block,0,size);stage=VoicePlaybackFailure.Stage.SINK_WRITE;
                int offset=0;
                while(offset<size){
                    check(deadline);int n=sink.write(block,offset,size-offset);
                    if(n<0||n>size-offset||(n&1)!=0)throw failure(VoicePlaybackFailure.Category.SINK_WRITE);
                    sink.updateReference();
                    if(n==0)clock.pause();
                    else{if(frames==0&&offset==0)milestone("sink_first_write");offset+=n;}
                }
                frames+=size/2;
            }
            stage=VoicePlaybackFailure.Stage.SINK_HEAD;
            while(true){
                check(deadline);long played=sink.playedFrames();
                if(played<0||played>frames)throw failure(VoicePlaybackFailure.Category.SINK_HEAD);
                if(played==frames)break;
                clock.pause();
            }
            check(deadline);milestone("sink_head_complete");completed=true;
        }catch(Exception error){
            interrupted=error instanceof InterruptedException;
            primary=VoicePlaybackFailure.safe(error,stage);
        }finally{
            input=null;
            try{in.close();}catch(Exception error){primary=append(primary,error,VoicePlaybackFailure.Stage.SOURCE_CLOSE);}
            if(!completed||primary!=null)try{sink.abort();}catch(Exception error){primary=append(primary,error,VoicePlaybackFailure.Stage.SINK_ABORT);}
            try{sink.close();}catch(Exception error){primary=append(primary,error,VoicePlaybackFailure.Stage.SINK_CLOSE);}
        }
        if(interrupted){
            InterruptedException safe=new InterruptedException("CANCELLED");
            safe.addSuppressed(primary);throw safe;
        }
        if(primary!=null)throw primary;
        return new Receipt(request,audio,generation,frames);
    }
    private static VoicePlaybackFailure append(VoicePlaybackFailure primary,Exception error,VoicePlaybackFailure.Stage stage){
        VoicePlaybackFailure safe=VoicePlaybackFailure.safe(error,stage);
        if(primary==null)return safe;
        if(safe!=primary&&primary.getSuppressed().length<3)primary.addSuppressed(safe);
        return primary;
    }
}
