package com.mist.connect;

/** Explicit outcome: all independent native resources are attempted, including after Error.
 * No exception messages/audio are logged. The primary exception is never replaced. */
public final class CaptureCleanup {
    public interface Effect { void run() throws Throwable; }
    public static final class Outcome {
        public final Throwable stopFailure, aecFailure, microphoneFailure;
        private Outcome(Throwable stop, Throwable aec, Throwable mic) {
            stopFailure=stop; aecFailure=aec; microphoneFailure=mic;
        }
        public boolean confirmed() {return stopFailure==null && aecFailure==null && microphoneFailure==null;}
        public Throwable preserve(Throwable primary) {
            Throwable result=primary;
            for(Throwable error:new Throwable[]{stopFailure,aecFailure,microphoneFailure}) {
                if(error==null)continue;
                if(result==null)result=error;
                else if(result!=error)result.addSuppressed(error);
            }
            return result;
        }
    }
    private static Throwable attempt(Effect effect) {
        if(effect==null)return null;
        try{effect.run();return null;}catch(Throwable failure){return failure;}
    }
    public static Outcome release(Effect stop, Effect aec, Effect microphone) {
        Throwable stopFailure=attempt(stop);
        Throwable aecFailure=attempt(aec);
        Throwable micFailure=attempt(microphone);
        return new Outcome(stopFailure,aecFailure,micFailure);
    }
    private CaptureCleanup() {}
}
