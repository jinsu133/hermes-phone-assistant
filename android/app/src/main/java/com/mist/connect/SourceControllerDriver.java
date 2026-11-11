package com.mist.connect;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One retained control worker, synchronous heartbeat exchange, no reconnect/replay.
 * IO is never performed with the playback owner gate held. */
public final class SourceControllerDriver {
    private final SourceServicePlaybackAdapter owner;
    private final SourceControllerTransport transport;
    private final LongSupplier elapsed;
    private final long initialBudget;
    private volatile boolean stopped;
    private Thread worker, preparingThread;
    private boolean attempted;
    private SourceControllerTransport.Connection connection;
    public SourceControllerDriver(SourceServicePlaybackAdapter owner,SourceControllerTransport transport,
                                  LongSupplier elapsed,long initialBudget) {
        if(initialBudget<1 || initialBudget>60000)throw new IllegalArgumentException("initial_budget");
        this.owner=Objects.requireNonNull(owner);this.transport=Objects.requireNonNull(transport);
        this.elapsed=Objects.requireNonNull(elapsed);this.initialBudget=initialBudget;
    }
    /** Lifecycle worker only, before any microphone opens. No restart on this driver. */
    public boolean prepare() throws IOException,InterruptedException {
        synchronized(this){
            if(stopped || attempted)return logFalse("prepare_entry stopped="+stopped+" attempted="+attempted);
            attempted=true;preparingThread=Thread.currentThread();
        }
        try {return prepareOnce();}
        catch(IOException | RuntimeException | InterruptedException failure){owner.controllerFailure(failure);throw failure;}
        finally{synchronized(this){preparingThread=null;}}
    }
    private boolean prepareOnce() throws IOException,InterruptedException {
        if(!owner.stopAndJoin(3000))return logFalse("prepare_stop_join");
        long attempt=owner.beginConnectionAttempt();
        long sent=elapsed.getAsLong();
        SourceControllerTransport.Connection c=transport.connect();
        long spent=elapsed.getAsLong()-sent;
        if(stopped || spent<0 || spent>=initialBudget)return logFalse("prepare_budget stopped="+stopped+" spent="+spent+" budget="+initialBudget);
        connection=c;
        if(!owner.installController(c,initialBudget-spent,attempt))return logFalse("prepare_install");
        if(!renew())return logFalse("prepare_renew");
        if(!owner.acknowledgeController(transport))return logFalse("prepare_ack");
        if(stopped)return logFalse("prepare_stopped_after_ack");
        synchronized(this){
        if(stopped)return false;
        worker=new Thread(()->{
            try {
                while(!stopped && owner.controllerCurrent()) {
                    if(owner.heartbeatDue() && !renew())break;
                    Thread.sleep(50);
                }
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(IOException failure){/* No payload/exception logging. */}
            finally{
                owner.revokeController(); // Logical OFF always precedes remote cancellation.
                try{transport.cancel(connection);}
                catch(IOException | RuntimeException failure){owner.controllerFailure(failure);}
            }
        },"voice-controller-heartbeat");
        worker.start();return true;
        }
    }
    /** Names the failing checkpoint without touching payloads, tokens, or screen text. */
    private static boolean logFalse(String checkpoint){
        android.util.Log.w("VoiceController","prepare false at "+checkpoint);
        return false;
    }
    boolean renew() throws IOException {
        ControllerLease.Flight f=owner.beginHeartbeat();
        if(f==null)return false;
        try{return owner.acceptHeartbeat(f,transport.heartbeat(f.connection));}
        catch(IOException | RuntimeException failure){owner.controllerFailure(failure);throw failure;}
    }
    /** Logical stop only; never waits for a socket/native call on the UI thread. */
    public void stop() {stopped=true;owner.revokeController();}
    public boolean stopAndJoin(long millis) throws InterruptedException {
        if(millis<1 || millis>5000)throw new IllegalArgumentException("join_bound");
        stop();final Thread old, preparing;
        synchronized(this){old=worker;preparing=preparingThread;}
        if(preparing==Thread.currentThread())return false;
        if(preparing!=null)preparing.join(millis);
        if(preparing!=null && preparing.isAlive()){owner.failCleanup();return false;}
        if(old==Thread.currentThread())return false;
        if(old!=null)old.join(millis);
        if(old!=null && old.isAlive()){owner.failCleanup();return false;}
        return !owner.blocked();
    }
}
