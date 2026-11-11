package com.mist.connect;

import java.util.function.LongSupplier;

/** Memory-only diagnostic preview. No persistence or logging dependency. */
public final class TransientTranscript {
    private final LongSupplier clock;
    private boolean visible;
    private boolean recording;
    private String text="";
    private long expires;
    private byte[] lastDigest;
    public TransientTranscript(LongSupplier clock) {this.clock=clock;}
    public synchronized void setVisible(boolean value) {visible=value;if(!value)clear();}
    public synchronized void setRecording(boolean value) {recording=value;clear();}
    public synchronized void update(String value,boolean complete) {
        if(!visible || !recording || value==null || value.trim().isEmpty())return;
        String next=(complete?"확정: ":"중간: ")+value.substring(0,Math.min(value.length(),160));
        byte[] digest;
        try {digest=java.security.MessageDigest.getInstance("SHA-256").digest(next.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
        if(!java.util.Arrays.equals(digest,lastDigest)) {lastDigest=digest;text=next;expires=clock.getAsLong()+10000;}
    }
    public synchronized String snapshot() {
        if(!visible || !recording || clock.getAsLong()>=expires)text="";
        return text;
    }
    public synchronized void clear() {text="";expires=0;lastDigest=null;}
}
