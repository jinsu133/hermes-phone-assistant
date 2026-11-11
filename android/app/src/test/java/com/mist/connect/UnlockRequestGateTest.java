package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class UnlockRequestGateTest {
 @Test public void authenticationMustActuallyUnlockDevice() {
   UnlockRequestGate g=new UnlockRequestGate(); long id=g.begin(100,120000);
   assertFalse(g.complete(id,200,false)); assertFalse(g.granted(id,200,false));
   id=g.begin(300,120000); assertTrue(g.complete(id,400,true)); assertTrue(g.granted(id,400,true));
   assertFalse(g.granted(id,400,false));
 }
 @Test public void cancellationRevokesEvenPreviouslyGrantedTicket() {
   UnlockRequestGate g=new UnlockRequestGate(); long id=g.begin(0,120000);
   assertTrue(g.complete(id,100,true)); g.cancel(id); assertFalse(g.granted(id,101,true));
 }
 @Test public void lateCallbackCannotAuthorizeNewRequest() {
   UnlockRequestGate g=new UnlockRequestGate(); long old=g.begin(0,120000); long fresh=g.begin(100,120000);
   assertFalse(g.complete(old,200,true)); assertTrue(g.pending(fresh,200));
 }
 @Test public void expiryNeverResumesRequest() {
   UnlockRequestGate g=new UnlockRequestGate(); long id=g.begin(100,120000);
   assertFalse(g.pending(id,120100)); assertFalse(g.complete(id,120100,true));
 }
}
