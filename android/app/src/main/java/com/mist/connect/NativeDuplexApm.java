// SPDX-License-Identifier: MIT
package com.mist.connect;
/** Thread-confined genuine AEC3 + residual WebRTC VAD, not acoustic authorization.
 * Load failure is explicit; there is no system-AEC or energy-threshold fallback. */
public final class NativeDuplexApm implements AutoCloseable {
    private static final class Loader {
        static final boolean AVAILABLE;
        static {boolean loaded=false;try{System.loadLibrary("hermes_duplex");loaded=true;}catch(UnsatisfiedLinkError absent){}AVAILABLE=loaded;}
        static void load(){if(!AVAILABLE)throw new UnsatisfiedLinkError("duplex_not_packaged");}
    }
    private final Thread owner=Thread.currentThread();
    private long handle;
    public NativeDuplexApm(){Loader.load();handle=create();if(handle==0)throw new IllegalStateException("duplex_create");}
    private void check(){if(Thread.currentThread()!=owner||handle==0)throw new IllegalStateException("duplex_owner");}
    public void render(short[] pcm){check();process(handle,pcm,null,0,true);}
    /** Speech-like residual only; echo, TV or a replay can also return true. */
    public boolean capture(short[] pcm,short[] output,int delayMillis){check();return process(handle,pcm,output,delayMillis,false)==1;}
    public void close(){if(handle==0)return;check();long h=handle;handle=0;release(h);}
    private static native long create();
    private static native void release(long handle);
    private static native int process(long handle,short[] pcm,short[] output,int delay,boolean reverse);
}
