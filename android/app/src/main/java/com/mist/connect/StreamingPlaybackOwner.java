package com.mist.connect;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Objects;

/** SOURCE/OFFLINE only. One instance must own ALL streaming playback in a client.
 * No threads, network endpoints, microphone, provider or service registration.
 * Caller pumps runNext() on an IO worker; concurrent pumps cannot allocate a sink.
 * Factories must be lazy: enqueue must not open a stream, synthesize, or own a sink.
 * Sink factory/operations run under the owner lock and must be bounded/nonblocking,
 * non-reentrant, and never wait for the IO worker. Source.open/read/close run outside
 * it and require bounded transport timeouts. A stuck IO worker blocks replacement.
 */
public final class StreamingPlaybackOwner {
    public enum Kind { PROGRESS, FINAL, APPROVAL_NOTICE }
    public interface Source { InputStream open() throws IOException; }
    public interface SinkFactory { StreamingPcmPlayer.Sink create(); }
    /** Historical notification only. Not an approval grant or microphone capability. */
    public static final class Eligibility {
        public final StreamingPcmPlayer.Receipt receipt;
        public final Kind kind;
        private Eligibility(StreamingPcmPlayer.Receipt receipt, Kind kind) {
            this.receipt=receipt; this.kind=kind;
        }
    }
    private static final class Job {
        final String request, audio; final long generation; final Kind kind; final Source source;
        boolean revoked;
        Job(String r,long g,String a,Kind k,Source s) {request=r;generation=g;audio=a;kind=k;source=s;}
    }
    private final Object lock=new Object();
    private final int capacity;
    private final SinkFactory factory;
    private final StreamingPcmPlayer.Clock clock;
    private final ArrayDeque<Job> queue=new ArrayDeque<>();
    private final java.util.HashSet<String> admittedAudio=new java.util.HashSet<>();
    private String request;
    private long generation=-1, sequence=-1;
    private boolean enabled, terminalQueued, blocked;
    private Job running;
    private GuardedSink sink;
    private StreamingPcmPlayer.Receipt accepted;
    private Eligibility eligibility;

    public StreamingPlaybackOwner(int capacity,SinkFactory factory,StreamingPcmPlayer.Clock clock) {
        if(capacity<1 || capacity>64)throw new IllegalArgumentException("capacity");
        this.capacity=capacity;this.factory=Objects.requireNonNull(factory);this.clock=Objects.requireNonNull(clock);
    }
    private static boolean id(String s) {return s!=null && s.matches("[A-Za-z0-9_-]{1,96}");}
    /** Strictly increasing, trusted controller epoch. Duplicate begin is rejected,
     * including after completion/OFF; retry requires explicit NEW epoch. */
    public boolean begin(String request,long generation) {
        if(!id(request) || generation<0)throw new IllegalArgumentException("identity");
        synchronized(lock) {
            if(blocked || generation<=this.generation)return false;
            invalidate();
            if(blocked)return false;
            this.request=request;this.generation=generation;sequence=-1;terminalQueued=false;enabled=true;
            admittedAudio.clear();
            return true;
        }
    }
    /** Progress is FIFO by strictly increasing admission sequence (no gap waiting).
     * Duplicate/out-of-order sequence is rejected without opening its source.
     * One terminal per epoch; it evicts queued progress and revokes running progress.
     * Capacity bounds waiting descriptors, not active sink; no PCM is queued here.
     */
    public boolean enqueue(String request,long generation,String audio,long sequence,Kind kind,Source source) {
        if(!id(audio) || sequence<0)throw new IllegalArgumentException("job");
        Objects.requireNonNull(kind);Objects.requireNonNull(source);
        synchronized(lock) {
            if(!enabled || blocked || !Objects.equals(this.request,request) || this.generation!=generation
                    || sequence<=this.sequence || terminalQueued)return false;
            if(admittedAudio.contains(audio) || admittedAudio.size()>=1024)return false;
            if(kind==Kind.PROGRESS && (queue.size()>=capacity || admittedAudio.size()>=1023))return false;
            if(kind!=Kind.PROGRESS) {
                queue.clear();accepted=null;eligibility=null;
                if(running!=null) revokeRunning();
                if(blocked)return false;
                terminalQueued=true;
            }
            this.sequence=sequence;admittedAudio.add(audio);
            queue.addLast(new Job(request,generation,audio,kind,source));return true;
        }
    }
    private boolean current(Job j) {
        return enabled && !blocked && running==j && !j.revoked
                && generation==j.generation && Objects.equals(request,j.request);
    }
    private void revokeRunning() {
        running.revoked=true;
        if(sink!=null)try {sink.abort();}catch(RuntimeException failure){poison();}
    }
    private void poison() {
        blocked=true;enabled=false;queue.clear();accepted=null;eligibility=null;
        if(running!=null)running.revoked=true;
    }
    private void invalidate() {
        enabled=false;queue.clear();accepted=null;eligibility=null;
        if(running!=null)revokeRunning();
    }
    /** Linearization: same lock as acceptance. Clears pending eligibility/receipts.
     * Local abort is immediate; transport cleanup remains the bounded IO worker's
     * responsibility. Does not assert remote provider/billing cancellation. */
    public void cancel() {synchronized(lock){invalidate();}}
    public void off() {cancel();}
    public boolean blocked() {synchronized(lock){return blocked;}}
    public int queued() {synchronized(lock){return queue.size();}}
    /** Diagnostic snapshot, NEVER permission for a later action. */
    public boolean isAccepted(StreamingPcmPlayer.Receipt receipt) {
        synchronized(lock){return enabled && !blocked && receipt!=null && accepted==receipt;}
    }
    /** Removes a pending historical event atomically. No mic/action activation.
     * A consumer must not turn a returned snapshot into later permission; a future
     * live listening transition must be implemented inside this ownership boundary. */
    public Eligibility takeEligibility() {
        synchronized(lock){Eligibility e=eligibility;eligibility=null;return e;}
    }
    /** Returns historical receipt or null (idle/busy/cancel/error), not permission.
     * Only player-produced complete-head receipts can enter the acceptance path.
     */
    public StreamingPcmPlayer.Receipt runNext() {
        final Job j;final GuardedSink guarded;
        synchronized(lock) {
            if(!enabled || blocked || running!=null || queue.isEmpty())return null;
            j=queue.removeFirst();running=j;
            try {
                guarded=new GuardedSink(j,Objects.requireNonNull(factory.create()));sink=guarded;
            } catch(RuntimeException failure) {
                // Factory owns cleanup if allocation throws. Unknown ownership fails closed.
                poison();running=null;return null;
            }
        }
        StreamingPcmPlayer.Receipt receipt=null;
        try {
            StreamingPcmPlayer player=new StreamingPcmPlayer(j.request,j.audio,j.generation,
                (r,a,g)->{synchronized(lock){return current(j);}},guarded,clock);
            receipt=player.play(Objects.requireNonNull(j.source.open()));
        } catch(IOException | RuntimeException failure) {
            // No raw exception/body retained. Failure cleanup below; no receipt.
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized(lock) {
                // Also handles source-open failure before player takes ownership.
                try {if(receipt==null)guarded.abort();}
                catch(RuntimeException failure){poison();}
                try {guarded.close();}catch(RuntimeException failure){poison();}
                // SINGLE completion/cancel linearization point, after confirmed close.
                if(receipt!=null && guarded.closed && current(j)
                        && j.request.equals(receipt.requestId) && j.audio.equals(receipt.audioId)
                        && j.generation==receipt.generation && receipt.frameCount>0) {
                    accepted=receipt;
                    if(j.kind!=Kind.PROGRESS)eligibility=new Eligibility(receipt,j.kind);
                } else {receipt=null;}
                running=null;sink=null;
            }
        }
        return receipt;
    }
    private final class GuardedSink implements StreamingPcmPlayer.Sink {
        final Job job;final StreamingPcmPlayer.Sink delegate;
        boolean aborted,closed;
        GuardedSink(Job job,StreamingPcmPlayer.Sink delegate){this.job=job;this.delegate=delegate;}
        private void check(){if(!current(job) || aborted || closed)throw new IllegalStateException("revoked_sink");}
        public void start(){synchronized(lock){check();delegate.start();}}
        public int write(byte[] b,int o,int n){synchronized(lock){check();return delegate.write(b,o,n);}}
        public long playedFrames(){synchronized(lock){check();return delegate.playedFrames();}}
        public void abort(){synchronized(lock){
            if(!aborted && !closed) {
                // Record success only AFTER stop returns; a thrown stop poisons owner.
                try {delegate.abort();aborted=true;}
                catch(RuntimeException failure){poison();throw failure;}
            }
        }}
        public void close(){synchronized(lock){
            if(!closed)try {delegate.close();closed=true;}
            catch(RuntimeException failure){poison();throw failure;}
        }}
    }
}
