package com.mist.connect;

import java.io.IOException;
import java.util.Map;

/** Explicit OFFLINE fixture only. Credential comes from fixture memory, never provider config.
 * This is NOT the production pairing/protected-IPC implementation. */
public final class SourceLoopbackControllerTransport implements SourceControllerTransport {
    private final SourceLoopbackPlaybackTransport wire;
    private final String profile, deviceEpoch;
    public SourceLoopbackControllerTransport(boolean explicitFixtureOptIn,int port,String fixtureCredential,
            String profile,String deviceEpoch,SourceServicePlaybackAdapter owner) {
        if(!explicitFixtureOptIn)throw new IllegalArgumentException("fixture_opt_in_required");
        if(profile==null || deviceEpoch==null || profile.isEmpty() || deviceEpoch.isEmpty()
                || profile.length()>256 || deviceEpoch.length()>256)throw new IllegalArgumentException("scope_invalid");
        this.profile=profile;this.deviceEpoch=deviceEpoch;
        wire=new SourceLoopbackPlaybackTransport(port,fixtureCredential,owner);
    }
    public Connection connect() throws IOException {
        Map<String,Object> o=SourceControllerJson.parse(wire.connect("{\"profile\":"+q(profile)+",\"device_epoch\":"+q(deviceEpoch)+"}"));
        SourceControllerJson.keys(o,"profile","session","host_epoch","device_epoch","epoch","challenge","stopped_epoch");
        if(!profile.equals(s(o,"profile")) || !deviceEpoch.equals(s(o,"device_epoch")))throw SourceControllerJson.bad();
        try{return new Connection(profile,s(o,"session"),s(o,"host_epoch"),deviceEpoch,
                SourceControllerJson.integer(o,"epoch"),s(o,"challenge"),SourceControllerJson.integer(o,"stopped_epoch"));}
        catch(IllegalArgumentException failure){throw SourceControllerJson.bad();}
    }
    static double heartbeatResponse(byte[] bytes) throws IOException {
        Map<String,Object> o=SourceControllerJson.parse(bytes);
        SourceControllerJson.keys(o,"status","lease_seconds");
        if(!"renewed".equals(o.get("status")) || !(o.get("lease_seconds") instanceof Number))throw SourceControllerJson.bad();
        double seconds=((Number)o.get("lease_seconds")).doubleValue();
        if(!Double.isFinite(seconds) || seconds<=0 || seconds>60)throw SourceControllerJson.bad();
        return seconds;
    }
    public double heartbeat(Connection c) throws IOException {return heartbeatResponse(wire.control("/heartbeat",scope(c)+"}"));}
    public void stopAck(Connection c) throws IOException {
        status(wire.control("/stop-ack",scope(c)+",\"challenge\":"+q(c.challenge)
                +",\"stopped_epoch\":"+c.stoppedEpoch+",\"closed\":true}"),"stopped");
    }
    public void cancel(Connection c) throws IOException {status(wire.control("/cancel",scope(c)+"}"),"revoked");}
    private static void status(byte[] bytes,String expected) throws IOException {
        Map<String,Object> o=SourceControllerJson.parse(bytes);SourceControllerJson.keys(o,"status");
        if(!expected.equals(o.get("status")))throw SourceControllerJson.bad();
    }
    private static String s(Map<String,Object> o,String k) throws IOException{return SourceControllerJson.string(o,k);}
    private static String q(String s){return SourceControllerJson.quote(s);}
    static String scope(Connection c) {return "{\"profile\":"+q(c.profile)+",\"session\":"+q(c.session)
            +",\"host_epoch\":"+q(c.hostEpoch)+",\"device_epoch\":"+q(c.deviceEpoch)+",\"epoch\":"+c.epoch;}
}
