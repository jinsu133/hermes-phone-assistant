// SPDX-License-Identifier: MIT
package com.mist.connect;

/** Service-owned one-shot bridge from the existing capture worker to existing cancellation.
 * No acoustic classifier or microphone ownership. Production callers must supply BOTH
 * worker and reference qualification (currently false); synthetic tests cannot change them.
 * Capture calls only prepare/start/endpoint. Blocking cancellation and joins are off capture.
 */
public final class DuplexTurnHandoff implements AutoCloseable {
    public enum Terminal { PENDING, CLEAN, UNKNOWN }
    public static final class TerminalGate {
        private final String generation, request;
        private String run;
        private Terminal state=Terminal.PENDING;
        public TerminalGate(String generation,String request){this.generation=generation;this.request=request;}
        public Terminal observe(boolean ok,String generation,String request,String run,String terminal,boolean cleanup){
            if(state==Terminal.UNKNOWN)return state;
            if(!ok || !this.generation.equals(generation) || !this.request.equals(request)
                    || !(terminal.equals("PENDING") || terminal.equals("CLEAN"))
                    || (!run.isEmpty() && !run.matches("run_[a-f0-9]{32}"))
                    || (this.run!=null && !this.run.equals(run)))return state=Terminal.UNKNOWN;
            if(!run.isEmpty())this.run=run;
            if(terminal.equals("CLEAN") && run.isEmpty())return state=Terminal.UNKNOWN;
            return state=terminal.equals("CLEAN") && cleanup?Terminal.CLEAN:Terminal.PENDING;
        }
    }
    public interface Cleanup { boolean run() throws Exception; }
    public interface Prepare { Cleanup prepare(PlaybackReference.Scope scope); }
    private final Prepare prepare;
    private final java.util.function.Predicate<PlaybackReference.Scope> current;
    private volatile PlaybackReference.Scope scope;
    private volatile Terminal terminal=Terminal.PENDING;
    private volatile boolean revoked, ended;
    private Thread worker;
    private ContinuousCaptureHandoff.Command command;
    public DuplexTurnHandoff(Prepare prepare,java.util.function.Predicate<PlaybackReference.Scope> current){
        this.prepare=java.util.Objects.requireNonNull(prepare);this.current=java.util.Objects.requireNonNull(current);
    }
    /** Capture thread only. prepare is strictly logical/owner-gated; never IO or teardown. */
    public boolean request(PlaybackReference.Scope expected,boolean qualified){
        if(!qualified || revoked || scope!=null || expected==null)return false;
        Cleanup cleanup=prepare.prepare(expected);
        if(cleanup==null){close();return false;}
        scope=expected;
        worker=new Thread(()->{
            boolean clean=false;
            try{clean=cleanup.run();}catch(Throwable failure){ /* no cleanup certificate on worker failure */ }
            synchronized(this){
                if(terminal!=Terminal.UNKNOWN && clean)terminal=Terminal.CLEAN;
                else {terminal=Terminal.UNKNOWN;erase();}
            }
        },"duplex-turn-cancel");
        try{worker.start();return true;}catch(RuntimeException | Error failure){failCleanup();throw failure;}
    }
    public boolean collecting(){return scope!=null && !revoked && !ended && terminal!=Terminal.UNKNOWN && current.test(scope);}
    public PlaybackReference.Scope scope(){return scope;}
    public synchronized void endpoint(ContinuousCaptureHandoff.Command value){
        if(value==null)return;
        if(revoked || ended || terminal==Terminal.UNKNOWN || value.scope!=scope){value.close();close();return;}
        command=value;ended=true;
    }
    /** Called from the actual capture finally, including rejection and native failure. */
    public synchronized void captureEnded(boolean clean){
        if(!clean)failCleanup();
        else if(scope!=null && (!ended || command==null))close();
    }
    /** Lifecycle only. Physical worker exit and remote terminal, NOT transfer permission.
     * A timeout/interruption/exception is sticky, including after a late worker success. */
    public boolean awaitCleanup(long millis) throws InterruptedException {
        if(millis<1 || millis>25000)throw new IllegalArgumentException("join_bound");
        Thread w=worker;
        try{if(w!=null)w.join(millis);}
        catch(InterruptedException failure){failCleanup();throw failure;}
        synchronized(this){
            if(w!=null && w.isAlive()){failCleanup();return false;}
            return terminal!=Terminal.UNKNOWN && (scope==null || terminal==Terminal.CLEAN);
        }
    }
    /** Legacy authority-bearing result remains strict; use awaitCleanup for OFF settlement. */
    public boolean join(long millis) throws InterruptedException {
        boolean clean=awaitCleanup(millis);
        return clean && !revoked;
    }
    /** Called under the retained owner's gate after capture + request + cancel joins. */
    public synchronized ContinuousCaptureHandoff.Command take(){
        if(revoked || terminal!=Terminal.CLEAN || !ended || !current.test(scope)){close();return null;}
        ContinuousCaptureHandoff.Command result=command;command=null;revoked=true;return result;
    }
    private void erase(){if(command!=null){command.close();command=null;}}
    private synchronized void failCleanup(){terminal=Terminal.UNKNOWN;close();}
    public synchronized void close(){revoked=true;erase();}
}
