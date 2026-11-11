package com.mist.connect;

import java.io.IOException;

/** Trusted in-process injection only; no token getter or diagnostic serialization.
 * A production implementation must originate in reviewed pairing/protected transport.
 * This source tree supplies only an explicit synthetic loopback fixture implementation. */
public interface SourceControllerTransport {
    enum FailureCode { AUTH, STALE, EXPIRED, CLEANUP, TRANSPORT, SCHEMA, REJECTED }
    final class Failure extends IOException {
        public final FailureCode code;
        public Failure(FailureCode code){super("controller_"+code.name().toLowerCase(java.util.Locale.ROOT));this.code=code;}
    }
    final class Connection {
        public final String profile, session, hostEpoch, deviceEpoch, challenge;
        public final long epoch, stoppedEpoch;
        public Connection(String profile,String session,String hostEpoch,String deviceEpoch,
                          long epoch,String challenge,long stoppedEpoch) {
            for(String s:new String[]{profile,session,hostEpoch,deviceEpoch,challenge})
                if(s==null || s.isEmpty() || s.length()>256)throw new IllegalArgumentException("scope_invalid");
            if(epoch<1 || stoppedEpoch<0 || stoppedEpoch!=epoch-1)throw new IllegalArgumentException("epoch_invalid");
            this.profile=profile;this.session=session;this.hostEpoch=hostEpoch;this.deviceEpoch=deviceEpoch;
            this.epoch=epoch;this.challenge=challenge;this.stoppedEpoch=stoppedEpoch;
        }
        // Deliberately inherit Object.toString(): never stringify raw scope.
    }
    Connection connect() throws IOException;
    double heartbeat(Connection connection) throws IOException;
    void stopAck(Connection connection) throws IOException;
    void cancel(Connection connection) throws IOException;
}
