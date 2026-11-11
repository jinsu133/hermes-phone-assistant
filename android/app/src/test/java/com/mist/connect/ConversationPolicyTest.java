package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class ConversationPolicyTest {
 @Test public void followupWaitsTenSecondsWithoutUploadingSilence() {
   SpeechEndGate g = new SpeechEndGate(10000); short[] silence = new short[1600];
   for(int i=0;i<99;i++) assertFalse(g.accept(silence,silence.length));
   assertTrue(g.accept(silence,silence.length)); assertFalse(g.hasSpeech());
 }
 @Test public void speechEndsAfterPauseNotAtFollowupDeadline() {
   SpeechEndGate g=new SpeechEndGate(10000); short[] speech=new short[1600]; java.util.Arrays.fill(speech,(short)1500);
   // Energy alone is not speech: inject meaningful recognizer progress.
   assertFalse(g.accept(speech,1600,new SpeechEndGate.Evidence("오늘",false,-1)));
   assertFalse(g.accept(speech,1600,new SpeechEndGate.Evidence("오늘 날씨",false,-1)));
   for(int i=0;i<13;i++) assertFalse(g.accept(new short[1600],1600));
   assertTrue(g.accept(new short[1600],1600)); assertTrue(g.hasSpeech());
 }
 @Test public void energyAloneCannotAuthorizeFollowupUpload() {
   SpeechEndGate g=new SpeechEndGate(10000); short[] noise=new short[1600]; java.util.Arrays.fill(noise,(short)1500);
   for(int i=0;i<99;i++) assertFalse(g.accept(noise,1600));
   assertTrue(g.accept(noise,1600)); assertFalse(g.hasSpeech());
   assertEquals(SpeechEndGate.Decision.NO_SPEECH,g.decision());
 }
 @Test public void exactStopOnly() {
   StopPhraseGate g = new StopPhraseGate();
   assertFalse(g.accept("스탑이라고 말해 주세요",true));
   assertFalse(g.accept("스탑",false)); assertFalse(g.accept("스탑",false)); assertFalse(g.accept("스탑",false));
   g.reset(); assertTrue(g.accept("STOP!",true));
 }
}
