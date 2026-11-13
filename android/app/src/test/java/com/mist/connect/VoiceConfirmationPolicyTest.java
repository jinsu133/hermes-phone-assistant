package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceConfirmationPolicyTest {
 @Test public void exactPhraseOnly() {
  String p=VoiceConfirmationPolicy.phrase("message");
  assertTrue(VoiceConfirmationPolicy.matches(p,"이 메시지 보내기 허용."));
  for(String s:new String[]{"네","좋아요","이 메시지 보내기 허용하지 마","이 메시지 보내기 허용 취소",""}) assertFalse(VoiceConfirmationPolicy.matches(p,s));
 }
 @Test public void otherLanguagesIgnoreCaseAndFinalPunctuationOnly() {
  assertTrue(VoiceConfirmationPolicy.matches("Allow this call","allow this call."));
  assertTrue(VoiceConfirmationPolicy.matches("इस कॉल की अनुमति दें","इस कॉल की अनुमति दें।"));
  assertFalse(VoiceConfirmationPolicy.matches("Allow this call","Don't allow this call"));
  assertFalse(VoiceConfirmationPolicy.matches("Allow this call","Allow this call later"));
 }
 @Test public void kindsDoNotShareAuthorization() {
  assertFalse(VoiceConfirmationPolicy.matches(VoiceConfirmationPolicy.phrase("payment"),VoiceConfirmationPolicy.phrase("general")));
 }
 @Test public void errorsAreStructuredAndRedacted() {
  assertEquals("SNAPSHOT_EXPIRED",VoiceConfirmationPolicy.errorCode("stale_snapshot"));
  assertEquals("SCREEN_LIMIT_EXCEEDED",VoiceConfirmationPolicy.errorCode("screen_too_large_for_confirmation"));
  assertEquals("ACTION_DENIED_OR_STALE_SCREEN",VoiceConfirmationPolicy.errorCode("private raw content"));
 }
 @Test public void structuralSearchAcrossPackages() {
  for(String pkg:new String[]{"com.example.notes","org.example.catalog","com.android.chrome"}) {
   assertTrue(ActionRiskPolicy.nativeSearch("input_text",pkg+":id/search_src_text","android.widget.EditText","검색",true,false,"환경측정"));
   assertTrue(ActionRiskPolicy.nativeSearch("click",pkg+":id/search_button","android.widget.ImageButton","검색",false,false,""));
  }
 }
 @Test public void labelsAndWebCannotForgeNativeRoles() {
  // Candidate search evidence is NOT execution permission: final risk classification wins.
  boolean candidate=ActionRiskPolicy.nativeSearch("click","x:id/buy","android.widget.Button","검색",false,false,"");
  assertTrue(candidate);
  assertEquals(PhoneActionPolicy.Decision.DENY,PhoneActionPolicy.decide("click","x","x:id/buy",
      "android.widget.Button","검색",false,false,false,false,candidate,"general"));
  assertFalse(ActionRiskPolicy.nativeSearch("click","x:id/search_button","android.widget.Button","검색 후 결제",false,false,""));
  assertFalse(ActionRiskPolicy.nativeSearch("click","x:id/search_button","android.widget.Button","검색",false,true,""));
  assertTrue(ActionRiskPolicy.nativeSearch("input_text","x:id/message_input","android.widget.EditText","검색",true,false,"hello"));
  assertFalse(ActionRiskPolicy.nativeSearch("input_text","x:id/message_input","android.widget.EditText","메시지",true,false,"hello"));
  assertFalse(ActionRiskPolicy.nativeSearch("input_text","x:id/message_input","android.widget.EditText","검색",true,true,"hello"));
  for(String id:new String[]{"send_button","call_button","pay_button"}) {
   boolean search=ActionRiskPolicy.nativeSearch("click","com.kakao.talk:id/"+id,"android.widget.Button","검색",false,false,"");
   assertTrue(search);
   PhoneActionPolicy.Decision verdict=PhoneActionPolicy.decide("click","com.kakao.talk","com.kakao.talk:id/"+id,
       "android.widget.Button","검색",false,false,false,false,search,"general");
   assertEquals(id.equals("send_button")?PhoneActionPolicy.Decision.MESSAGE:
       id.equals("call_button")?PhoneActionPolicy.Decision.CALL:PhoneActionPolicy.Decision.PAYMENT,verdict);
  }
 }
 @Test public void searchDoesNotExecuteScriptsOrSubmitMultiline() {
  for(String s:new String[]{"javascript:alert(1)","data:text/html,a","intent://x","file:///private","a\nb"}) assertFalse(ActionRiskPolicy.safeQuery(s));
  assertTrue(ActionRiskPolicy.safeQuery("https://example.com"));
 }
}
