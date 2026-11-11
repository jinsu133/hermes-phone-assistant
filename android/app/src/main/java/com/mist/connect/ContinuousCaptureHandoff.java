package com.mist.connect;

import java.util.Arrays;

/** Single-capture continuity used by DuplexCaptureWorker on the existing AudioRecord.
 * A caller feeds exactly contiguous 10ms 16k mono frames from ONE capture stream.
 * Interruption pins a bounded prefix without reopening the microphone. Full endpoint
 * is required to extract a candidate. Candidate PCM confers NO dispatch/approval authority.
 * Native VAD may pin an UNQUALIFIED diagnostic candidate. Production command transfer
 * additionally requires the service's scoped clean-terminal handoff and both acoustic
 * gates; current PlaybackReference.authorizesBargeIn() is always false.
 */
public final class ContinuousCaptureHandoff {
    public static final class Command implements AutoCloseable {
        public final PlaybackReference.Scope scope;
        public final long firstFrame;
        public final short[] pcm;
        private Command(PlaybackReference.Scope scope,long first,short[] pcm){this.scope=scope;firstFrame=first;this.pcm=pcm;}
        public void close(){Arrays.fill(pcm,(short)0);}
    }
    private final PlaybackReference.Scope scope;
    private final short[] ring;
    private long next, retained, firstNanos=-1, commandStart=-1;
    private boolean revoked;
    public ContinuousCaptureHandoff(PlaybackReference.Scope scope,int capacityFrames) {
        if(capacityFrames<160 || capacityFrames>512000 || capacityFrames%160!=0)
            throw new IllegalArgumentException("capture_bound");
        this.scope=java.util.Objects.requireNonNull(scope);ring=new short[capacityFrames];
    }
    public synchronized boolean append(PlaybackReference.Scope expected,long first,long monotonicNanos,short[] pcm) {
        if(revoked || expected!=scope)return false;
        if(pcm==null || pcm.length!=160 || first!=next || monotonicNanos<=0
                || (firstNanos>=0 && Math.abs(monotonicNanos-(firstNanos+first*62500L))>2000000L)) {
            revoke();return false;
        }
        if(firstNanos<0)firstNanos=monotonicNanos;
        if(next-retained+pcm.length>ring.length) {
            if(commandStart>=0){revoke();return false;} // never truncate a pinned command
            retained=next+pcm.length-ring.length;
        }
        for(int i=0;i<pcm.length;i++)ring[(int)((next+i)%ring.length)]=pcm[i];
        next+=pcm.length;return true;
    }
    public synchronized boolean interrupt(PlaybackReference.Scope expected,long onsetFrame) {
        if(revoked || expected!=scope || commandStart>=0 || onsetFrame<retained || onsetFrame>=next)return false;
        commandStart=onsetFrame;
        while(retained<onsetFrame)ring[(int)(retained++%ring.length)]=0;
        return true;
    }
    public synchronized Command finish(PlaybackReference.Scope expected,long endFrame,boolean wholeEndpoint) {
        if(revoked || expected!=scope || commandStart<0 || !wholeEndpoint || endFrame!=next)return null;
        short[] copy=new short[(int)(next-commandStart)];
        for(int i=0;i<copy.length;i++)copy[i]=ring[(int)((commandStart+i)%ring.length)];
        Command result=new Command(scope,commandStart,copy);revoke();return result;
    }
    public synchronized void revoke(){revoked=true;Arrays.fill(ring,(short)0);}
}
