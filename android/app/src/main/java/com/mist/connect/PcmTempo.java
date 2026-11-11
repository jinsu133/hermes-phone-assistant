// SPDX-License-Identifier: MIT
package com.mist.connect;
import java.util.Arrays;
/** App-owned, bounded waveform-similarity overlap/add (WSOLA), PCM16LE/24k mono.
 * No resampling or Android time stretching. Search +/-5ms, grain 20ms, overlap 10ms.
 * Each <=40ms source block is transformed completely; exact cumulative output duration
 * is rounded once, not per write. Boundary intelligibility still requires listening.
 * Newly authored implementation, no SoundTouch (LGPL) / Sonic code copied. */
public final class PcmTempo implements AutoCloseable {
    private static final int HOP=240, GRAIN=480, SEARCH=120;
    private final float speed;
    private long source, rendered;
    public PcmTempo(float speed){this.speed=SpeechPlaybackPolicy.requireSpeed(speed);}
    public byte[] transform(byte[] pcm,int offset,int bytes){
        if(pcm==null||offset<0||bytes<2||bytes>1920||(bytes&1)!=0||offset>pcm.length-bytes)
            throw new IllegalArgumentException("tempo_block");
        int n=bytes/2;
        long target=Math.round((source+n)/(double)speed);
        int m=(int)(target-rendered);
        if(m<0)throw new IllegalArgumentException("tempo_duration");
        int hop=Math.min(HOP,Math.max(1,n/2)),grain=Math.min(n,hop*2);
        short[] in=new short[n];short[] out=new short[m];
        for(int i=0;i<n;i++)in[i]=(short)((pcm[offset+i*2]&255)|(pcm[offset+i*2+1]<<8));
        if(speed==1f)System.arraycopy(in,0,out,0,n);
        else {
            for(int i=0;i<Math.min(grain,m);i++)out[i]=in[Math.min(i,n-1)];
            for(int pos=hop;pos<m;pos+=hop){
                int nominal=(int)Math.round(pos*(double)speed);
                int lo=Math.max(0,Math.min(n-grain,nominal-SEARCH));
                int hi=Math.max(lo,Math.min(n-grain,nominal+SEARCH));
                int overlap=Math.min(hop,m-pos);
                int best=lo;double score=-Double.MAX_VALUE;
                for(int at=lo;at<=hi;at++){
                    double dot=0,a=1,b=1;
                    for(int j=0;j<overlap;j++){
                        double x=out[pos+j],y=in[Math.min(at+j,n-1)];dot+=x*y;a+=x*x;b+=y*y;
                    }
                    double corr=dot/Math.sqrt(a*b);
                    if(corr>score){score=corr;best=at;}
                }
                for(int j=0;j<Math.min(grain,m-pos);j++){
                    int y=in[Math.min(best+j,n-1)];
                    out[pos+j]=(short)(j<hop?((long)out[pos+j]*(hop-j)+(long)y*j)/hop:y);
                }
            }
        }
        byte[] result=new byte[m*2];
        for(int i=0;i<m;i++){result[i*2]=(byte)out[i];result[i*2+1]=(byte)(out[i]>>8);}
        Arrays.fill(in,(short)0);Arrays.fill(out,(short)0);source+=n;rendered=target;return result;
    }
    public long sourceFrames(){return source;}
    public long renderFrames(){return rendered;}
    public void close(){source=rendered=0;}
}
