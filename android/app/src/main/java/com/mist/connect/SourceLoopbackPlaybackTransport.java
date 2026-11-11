package com.mist.connect;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Bounded same-device authenticated wire shared by fixture and paired normal adapters.
 * No import/start side effects, provisioning, retries, redirects, remote hosts or TTS credentials.
 * Exact full scope is supplied by its controller and validated again by the host.
 * NormalVoiceStream owns production descriptor/receipt/listening semantics.
 */
public final class SourceLoopbackPlaybackTransport {
    private final int port;
    private final String bearer;
    private final SourceServicePlaybackAdapter owner;
    public SourceLoopbackPlaybackTransport(int port,String bearer,SourceServicePlaybackAdapter owner) {
        if(port<1 || port>65535 || bearer==null || !bearer.matches("[A-Za-z0-9_-]{32,512}"))
            throw new IllegalArgumentException("fixture_connection");
        this.port=port;this.bearer=bearer;this.owner=java.util.Objects.requireNonNull(owner);
    }
    private HttpURLConnection request(String path,String json) throws IOException {
        byte[] body=json.getBytes(StandardCharsets.UTF_8);
        if(body.length<2 || body.length>4096)throw new IOException("body_limit");
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setConnectTimeout(2000);c.setReadTimeout(2000);
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+bearer);
        c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(body.length);
        try(OutputStream out=c.getOutputStream()){out.write(body);}
        catch(IOException failure){c.disconnect();throw VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.TRANSPORT_OPEN);}
        finally {java.util.Arrays.fill(body,(byte)0);}
        return c;
    }
    public InputStream stream(String fullScope) throws IOException {
        HttpURLConnection c=request("/stream",fullScope);
        c.setReadTimeout(5000); // bounded incremental read; session cleanup joins conservatively
        try {
            int status=c.getResponseCode();
            if(status!=200)throw new VoicePlaybackFailure(VoicePlaybackFailure.Stage.TRANSPORT_OPEN,VoicePlaybackFailure.Category.HTTP,status);
            return new FilterInputStream(c.getInputStream()) {
                private boolean attempted;
                public void close() throws IOException {
                    synchronized(this){if(attempted)return;attempted=true;}
                    // Both operations may block: SourcePcmPlaybackSession's tracked closer
                    // owns them, never the owner gate or the remote-cancel worker.
                    try{c.disconnect();}finally{super.close();}
                }
            };
        } catch(IOException failure){c.disconnect();throw VoicePlaybackFailure.safe(failure,VoicePlaybackFailure.Stage.TRANSPORT_OPEN);}
    }
    byte[] control(String path,String scope) throws IOException {
        HttpURLConnection c=request(path,scope);
        try {
            int status=c.getResponseCode();
            if(status!=200)throw new SourceControllerTransport.Failure(
                status==401?SourceControllerTransport.FailureCode.AUTH:
                status==409?SourceControllerTransport.FailureCode.STALE:
                status==410?SourceControllerTransport.FailureCode.EXPIRED:
                status==423?SourceControllerTransport.FailureCode.CLEANUP:SourceControllerTransport.FailureCode.REJECTED);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[512];int n;
                long deadline=System.nanoTime()+10_000_000_000L;
                while((n=in.read(b))!=-1){
                    if(System.nanoTime()>deadline || out.size()+n>4096)throw new IOException("response_limit");
                    out.write(b,0,n);
                }
                return out.toByteArray();
            }
        } finally {c.disconnect();}
    }
    /** Fixture owns parsing and exact epoch/challenge matching. Never a live session lease. */
    public byte[] connect(String profileAndDeviceEpoch) throws IOException {return control("/connect",profileAndDeviceEpoch);}
    public byte[] stopAck(String exactAckScope,long joinMillis) throws IOException,InterruptedException {
        return owner.stopAndAcknowledge(joinMillis,()->control("/stop-ack",exactAckScope));
    }
    public byte[] receipt(String exactReceiptScope) throws IOException {return control("/receipt",exactReceiptScope);}
    public byte[] cancel(String connectionScope) throws IOException {owner.off();return control("/cancel",connectionScope);}
}
