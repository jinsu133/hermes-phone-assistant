package com.mist.connect;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;

/** Private metadata journal. No payload/string extension fields. Producers never do disk IO.
 * No automatic deletion: quota exhaustion refuses evidence readiness, not voice operation.
 * flush uses fsync on the writer, but abrupt death/power loss can still lose queued records. */
public final class VoiceDiagnosticJournal {
    public enum Event { TRACE, FAILURE, PLAYBACK_FAILURE, EXCEPTION, DUPLEX_OPEN, DUPLEX_EXIT,
        SESSION_OPEN, SESSION_CLOSE, GAP, RECOVERY, FLUSH }
    public static final class Record {
        final Event event; final String fields; final long wall=System.currentTimeMillis(), mono=System.nanoTime();
        private Record(Event event,String fields){this.event=event;this.fields=fields;}
    }
    private static final Set<String> STAGES=new HashSet<>(Arrays.asList(
        "unknown","start_busy","start_mic_permission","start_token","host_route_contract",
        "controller_status","controller_stream","controller_protocol","controller_create","controller_prepare","controller_capture","controller_begin","controller_work",
        "voice_request","progress_play","capture_exit","service_destroy","abort_thread_failed","aec_release",
        "capture_primary_failed","capture_start","controller_connected","controller_failure","controller_revoked",
        "descriptor_bound","factory_failed","heartbeat_accepted","heartbeat_rejected","heartbeat_send","join_confirmed",
        "join_unconfirmed","lease_expired","listening_start","mic_release","mic_stop","native_abort_begin","native_abort_end",
        "off_revoked","play_failed","playback_close_failed","request_accepted","stop_ack_accepted","stop_ack_send",
        "worker_abort_failed","sink_start","sink_first_write","pcm_eos","sink_head_complete","receipt_accepted",
        "service_controller_failed","service_request_failed","service_progress_failed","service_approval_failed",
        "service_capture_failed","service_playback_failed","diagnostic_sink_failed","sink_play_failed","sink_write_failed"));
    private static final Set<String> TYPES=new HashSet<>(Arrays.asList("IllegalStateException","IllegalArgumentException",
        "IOException","EOFException","SocketException","SocketTimeoutException","InterruptedIOException",
        "InterruptedException","SecurityException","UnsatisfiedLinkError","NoClassDefFoundError","OutOfMemoryError",
        "NullPointerException","RuntimeException","Exception","Error","Throwable","VoicePlaybackFailure","BridgeException","Failure"));
    static { for(VoicePlaybackFailure.Stage s:VoicePlaybackFailure.Stage.values())STAGES.add(s.name()); }
    private static final Set<String> CATEGORIES=new HashSet<>(Arrays.asList("none","IOError","Interrupted","OtherError","AUTH","STALE","EXPIRED","CLEANUP","TRANSPORT","SCHEMA","REJECTED","PROTOCOL","TIMEOUT"));
    static { for(VoicePlaybackFailure.Category c:VoicePlaybackFailure.Category.values())CATEGORIES.add(c.name()); }
    public static String category(String input){return input!=null&&input.length()<=48&&CATEGORIES.contains(input)?input:"unknown";}
    public static String stage(String input){return input!=null&&input.length()<=48&&STAGES.contains(input)?input:"unknown";}
    public static String type(Throwable t){if(t==null)return "none";String n=t.getClass().getSimpleName();return TYPES.contains(n)?n:"OtherThrowable";}
    public static Record failure(Event event,String stage,Throwable error){
        if(event!=Event.FAILURE&&event!=Event.EXCEPTION)throw new IllegalArgumentException("event");
        StringBuilder b=new StringBuilder("stage=").append(stage(stage)).append(" type=").append(type(error));
        if(error instanceof VoicePlaybackFailure){VoicePlaybackFailure f=(VoicePlaybackFailure)error;
            b.append(" failure_stage=").append(f.stage.name()).append(" category=").append(f.category.name()).append(" http_status=").append(f.httpStatus);}
        // Bounded identity graph: parent index and edge kind, no messages/stacks/class names outside allowlist.
        Throwable[] nodes=new Throwable[8];int[] parents=new int[8];String[] edges=new String[8];
        int count=0;if(error!=null){nodes[count]=error;parents[count]=-1;edges[count++]="root";}
        boolean truncated=false;
        for(int i=0;i<count;i++){
            b.append(" graph_").append(i).append('=').append(parents[i]).append(':').append(edges[i]).append(':').append(type(nodes[i]));
            Throwable cause=nodes[i].getCause();Throwable[] suppressed=nodes[i].getSuppressed();
            for(int k=-1;k<Math.min(suppressed.length,3);k++){
                Throwable child=k<0?cause:suppressed[k];if(child==null)continue;
                boolean seen=false;for(int j=0;j<count;j++)if(nodes[j]==child)seen=true;
                if(seen)continue;if(count==8){truncated=true;continue;}
                nodes[count]=child;parents[count]=i;edges[count++]=k<0?"cause":"suppressed";
            }
            if(suppressed.length>3)truncated=true;
        }
        return new Record(event,b.append(" graph_truncated=").append(truncated).toString());
    }
    public static Record playback(String stage,String category){
        // Legacy callers pass strings; never serialize arbitrary categories.
        String c=category(category);
        return new Record(Event.PLAYBACK_FAILURE,"stage="+stage(stage)+" category="+c);
    }
    public static Record trace(String input){
        StringBuilder b=new StringBuilder();boolean found=false;Set<String> seen=new HashSet<>();
        // Bound parsing/allocation even if a caller accidentally sends a transcript.
        if(input!=null&&input.length()<=2048)for(String token:input.split(" ",32)){
            int eq=token.indexOf('=');if(eq<0)continue;String k=token.substring(0,eq),v=token.substring(eq+1);if(!seen.add(k))continue;
            if(k.equals("stage")){b.append(" stage=").append(stage(v));found=true;}
            else if(k.equals("mono_ns")&&v.matches("-?[0-9]{1,19}")){
                // Adapter event time is distinct from the journal enqueue time on the record line.
                try{b.append(" event_mono_ns=").append(Long.parseLong(v));}catch(NumberFormatException ignored){}
            }
            else if(k.matches("secondary_[0-2]")){
                int colon=v.indexOf(':');
                if(colon>0)b.append(' ').append(k).append('=').append(stage(v.substring(0,colon))).append(':').append(category(v.substring(colon+1)));
            }
            else if(Arrays.asList("owner_local","request_local","audio_local","generation","connection_local","lease_elapsed_ms").contains(k)&&v.matches("-?[0-9]{1,19}")){
                try{b.append(' ').append(k).append('=').append(Long.parseLong(v));}catch(NumberFormatException ignored){}
            }
            else if(k.equals("error")||k.equals("category"))b.append(' ').append(k).append('=').append(category(v));
            else if(k.equals("failure_stage"))b.append(" failure_stage=").append(stage(v));
            else if(k.equals("http_status")&&v.matches("[1-5][0-9]{2}|0"))b.append(" http_status=").append(v);
            // Remote correlation, arbitrary error fields, query strings and payloads are deliberately omitted.
        }
        if(!found)b.append(" stage=").append(stage(input==null||input.length()>2048?null:input.split(" ",2)[0]));
        return new Record(Event.TRACE,b.toString().trim()+" latency_scope=app_stage_monotonic_not_speech_end");
    }
    public static Record duplex(boolean opened,DuplexCaptureWorker.FailureSnapshot s,boolean clean,Throwable primary,Throwable cleanup){
        String fields="opened="+opened+" cleanup_confirmed="+clean+" primary_type="+type(primary)+" cleanup_type="+type(cleanup);
        if(s!=null)fields+=" reason="+s.reason.name()+" now_ns="+s.nowNanos+" capture_ns="+s.captureNanos+" capture_age_ns="+s.captureAgeNanos
            +" reference_ns="+s.referenceNanos+" render_ns="+s.renderNanos+" render_age_ns="+s.renderAgeNanos+" capture_frames="+s.captureFrames+" render_frames="+s.renderFrames;
        return new Record(Event.DUPLEX_EXIT,fields);
    }
    public static Record nativeOpen(boolean opened,Throwable failure){return new Record(Event.DUPLEX_OPEN,"opened="+opened+" type="+type(failure));}
    public static String display(Record record){return record.event.name()+" "+record.fields;}

    private final File dir;
    public final String session=UUID.randomUUID().toString();
    private final long pid,version,quota,segmentLimit;private final int capacity,maxFiles;
    private final ConcurrentLinkedQueue<Record> queue=new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued=new AtomicInteger(), producers=new AtomicInteger();
    private final AtomicLong dropped=new AtomicLong(),accepted=new AtomicLong(),written=new AtomicLong();
    private final AtomicBoolean closing=new AtomicBoolean();
    private volatile boolean ready,stopped,incomplete;
    private volatile String problem="starting";
    private volatile long sequence,previousUnclosed;private int lossyCloses;
    private final Thread writer;
    private FileOutputStream out;private long total,segmentBytes;private int segment,files;private long reportedDrops;
    private final Runnable beforeDrain;
    private static final class CapacityException extends IOException {}
    public VoiceDiagnosticJournal(File parent,long pid,long version){this(parent,pid,version,65536,8*1024*1024,256,256,()->{});}
    // Package-private bounded fault/barrier injection, never a production readiness override.
    VoiceDiagnosticJournal(File parent,long pid,long version,long segmentLimit,long quota,int capacity,int maxFiles,Runnable beforeDrain){
        if(segmentLimit<1024||quota<4096||capacity<1||maxFiles<2)throw new IllegalArgumentException("limits");
        this.dir=new File(parent,"voice-journal");this.pid=pid;this.version=version;this.segmentLimit=segmentLimit;
        this.quota=quota;this.capacity=capacity;this.maxFiles=maxFiles;this.beforeDrain=beforeDrain;
        writer=new Thread(this::run,"voice-diagnostic-writer");writer.setDaemon(true);writer.start();
    }
    public boolean offer(Record r){
        Objects.requireNonNull(r);producers.incrementAndGet();
        try{
            if(closing.get()||stopped){drop("closed");markLateDrop();return false;}
            int size=queued.incrementAndGet();if(size>capacity){queued.decrementAndGet();drop("queue_overflow");return false;}
            queue.add(r);accepted.incrementAndGet();return true;
        }finally{producers.decrementAndGet();}
    }
    private void drop(String why){dropped.incrementAndGet();bad(why);}
    /** Post-close rejection cannot rewrite a closed marker; a bounded sidecar keeps that
     * known loss visible to the next process instead of leaving a "healthy" marker. */
    private void markLateDrop(){
        if(!stopped&&!closing.get())return;
        try{File f=new File(dir,session+".late-drop");if(!f.exists())f.createNewFile();}catch(IOException ignored){}
    }
    public void reportPreAttachDrops(long count){if(count>0){dropped.addAndGet(count);bad("before_attach");}}
    private void bad(String why){incomplete=true;problem=why;}
    public boolean testReady(){return ready&&!incomplete&&!closing.get()&&!stopped;}
    public String health(){return incomplete?"incomplete":stopped?"closed":ready?"healthy":"starting";}
    public String problem(){return problem;}
    public long dropped(){return dropped.get();} public long written(){return written.get();}
    public long accepted(){return accepted.get();} public int pending(){return queued.get();}
    public long sequence(){return sequence;} public long previousUnclosed(){return previousUnclosed;}
    public int previousLossyCloses(){return lossyCloses;}
    public boolean stopped(){return stopped;}
    public void closeAsync(){closing.set(true);}
    /** Only call from test/export worker, never audio or owner locks. */
    public boolean awaitClosed(long millis)throws InterruptedException{writer.join(millis);return stopped;}
    /** Ordered flush record; no producer waits. Status sequence is the durable writer watermark. */
    public void flushAsync(){offer(new Record(Event.FLUSH,"requested=true"));}
    private void run(){
        try{
            initialize();append(new Record(Event.SESSION_OPEN,"previous_unclosed="+previousUnclosed),true);
            if(previousUnclosed>0){bad("previous_unclosed");append(new Record(Event.RECOVERY,"previous_unclosed="+previousUnclosed),true);}
            if(lossyCloses>0){bad("previous_incomplete_close");append(new Record(Event.RECOVERY,"previous_incomplete_close="+lossyCloses),true);}
            ready=true;if(!incomplete)problem="none";
            beforeDrain.run();
            while(true){
                Record r=queue.poll();
                if(r!=null){queued.decrementAndGet();
                    try{append(r,false);written.incrementAndGet();}catch(CapacityException e){drop("storage_cap");}
                    catch(IOException e){drop("storage_io");throw e;}
                }
                long d=dropped.get();if(d!=reportedDrops){
                    try{append(new Record(Event.GAP,"dropped="+d+" health=incomplete"),true);reportedDrops=d;}catch(IOException e){bad("storage_unavailable_or_cap");}
                }
                if(closing.get()&&producers.get()==0&&queue.isEmpty())break;
                if(r==null)Thread.sleep(10);
            }
            append(new Record(Event.SESSION_CLOSE,"dropped="+dropped.get()+" health="+health()),true);
            out.close();out=null;
            // Existence proves journal drain/close, NOT microphone/playback cleanup.
            File marker=new File(dir,session+".closed");
            String state="closed health="+health()+" dropped="+dropped.get();
            try(FileOutputStream m=new FileOutputStream(marker)){m.write(state.getBytes(StandardCharsets.UTF_8));m.getFD().sync();}
        }catch(Throwable e){closing.set(true);bad("writer_io_or_initialization");
            while(producers.get()!=0)Thread.yield();
            Record r;while((r=queue.poll())!=null){queued.decrementAndGet();dropped.incrementAndGet();}}
        finally{ready=false;stopped=true;if(out!=null)try{out.close();}catch(IOException e){bad("close_io");}}
    }
    private void initialize()throws IOException{
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("directory");
        File[] old=dir.listFiles();if(old==null||old.length>maxFiles)throw new IOException("file_cap");
        Set<String> sessions=new HashSet<>(),closed=new HashSet<>();int lossyClosed=0;
        for(File f:old){if(!f.isFile())throw new IOException("entry");total+=f.length();files++;
            String n=f.getName();if(n.matches("[a-f0-9-]{36}-[0-9]{4}\\.log"))sessions.add(n.substring(0,36));
            if(n.matches("[a-f0-9-]{36}\\.closed")){
                String id=n.substring(0,36);closed.add(id);
                // Legacy marker bytes or any non-clean close keep the known loss sticky.
                byte[] data=new byte[(int)Math.min(f.length(),256)];
                try(FileInputStream in=new FileInputStream(f)){int read=0;while(read<data.length){int n2=in.read(data,read,data.length-read);if(n2<0)break;read+=n2;}}
                String text=new String(data,StandardCharsets.UTF_8).trim();
                File late=new File(dir,id+".late-drop");
                if(!text.equals("closed health=healthy dropped=0")||late.exists())lossyClosed++;
            }
        }
        sessions.removeAll(closed);previousUnclosed=sessions.size();lossyCloses=lossyClosed;
        // Leave room for current close marker and gap/close records. Never prune existing sessions.
        if(total+4096>quota||files+2>maxFiles)throw new IOException("cap");
    }
    private void append(Record r,boolean control)throws IOException{
        long next=sequence+1;
        String line="schema=1 session="+session+" seq="+next+" wall_ms="+r.wall+" mono_ns="+r.mono+" pid="+pid+" version="+version+" event="+r.event+" "+r.fields+"\n";
        byte[] bytes=line.getBytes(StandardCharsets.UTF_8);
        if(total+bytes.length+1>quota-(control?0:2048))throw new CapacityException();
        if(out==null||(!control&&segmentBytes+bytes.length>segmentLimit)){
            if(files+2>maxFiles)throw new CapacityException();
            if(out!=null){out.getFD().sync();out.close();out=null;}
            File f=new File(dir,session+"-"+String.format(Locale.ROOT,"%04d",segment++)+".log");
            if(!f.createNewFile())throw new IOException("collision");out=new FileOutputStream(f);files++;segmentBytes=0;
        }
        out.write(bytes);total+=bytes.length;segmentBytes+=bytes.length;out.flush();out.getFD().sync();sequence=next;
    }
}
