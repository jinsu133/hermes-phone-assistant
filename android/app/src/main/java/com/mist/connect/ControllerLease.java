package com.mist.connect;

import java.util.Objects;
import java.util.function.LongSupplier;

/** State owned exclusively by SourceServicePlaybackAdapter.lock. No IO or native cleanup. */
final class ControllerLease {
    static final class Flight {
        final SourceControllerTransport.Connection connection;
        final long sent, serial;
        Flight(SourceControllerTransport.Connection c,long sent,long serial) {this.connection=c;this.sent=sent;this.serial=serial;}
    }
    final LongSupplier clock;
    SourceControllerTransport.Connection connection;
    Flight flight;
    long deadline, serial, renewAt;
    boolean required, revoked, renewed, acknowledged, acknowledging;
    ControllerLease(LongSupplier clock) {this.clock=Objects.requireNonNull(clock);}
    void install(SourceControllerTransport.Connection c,long initialBudget) {
        if(initialBudget<1 || initialBudget>60000)throw new IllegalArgumentException("initial_budget");
        long now=clock.getAsLong();
        if(now<0 || now>Long.MAX_VALUE-initialBudget)throw new IllegalArgumentException("clock_range");
        connection=Objects.requireNonNull(c);required=true;revoked=false;renewed=false;
        acknowledged=false;acknowledging=false;flight=null;deadline=now+initialBudget;renewAt=now;
    }
    boolean alive() {return required && !revoked && clock.getAsLong()<deadline;}
    boolean permitted() {return !required || (alive() && renewed && acknowledged && !acknowledging);}
    Flight begin() {
        if(!alive() || flight!=null)return null;
        flight=new Flight(connection,clock.getAsLong(),++serial);return flight;
    }
    boolean accept(Flight f,double seconds) {
        if(f==null || flight!=f || connection!=f.connection || !alive())return false;
        flight=null;
        if(!Double.isFinite(seconds) || seconds<=0 || seconds>60)return false;
        long budget=(long)Math.floor(seconds*1000.0);
        if(budget<1 || f.sent>Long.MAX_VALUE-budget || clock.getAsLong()>=f.sent+budget)return false;
        deadline=f.sent+budget;renewAt=f.sent+Math.max(1,budget/3);renewed=true;return true;
    }
    void revoke() {revoked=true;renewed=false;acknowledged=false;flight=null;}
}
