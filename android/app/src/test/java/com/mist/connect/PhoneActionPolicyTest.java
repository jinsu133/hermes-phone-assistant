package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneActionPolicyTest {
 private PhoneActionPolicy.Decision d(String op,String id,String label,boolean safe,String kind) {
  return PhoneActionPolicy.decide(op,"com.kakao.talk","com.kakao.talk:id/"+id,"android.widget.Button",label,false,false,false,false,safe,kind);
 }
 @Test public void supportedPreparationNeverPrompts() {
  for(String op:new String[]{"click","input_text","submit_search","scroll_forward","scroll_backward"})
   assertEquals(PhoneActionPolicy.Decision.ROUTINE,d(op,"search_input","검색",true,"general"));
  for(String op:new String[]{"home","back","recents","notifications","open_app","list_apps","read_node"})
   assertEquals(PhoneActionPolicy.Decision.ROUTINE,d(op,"","",false,""));
 }
 @Test public void finalSendIncludingKakaoNeedsExactConfirmation() {
  assertEquals(PhoneActionPolicy.Decision.MESSAGE,d("click","send_button","",true,"general"));
  assertEquals(PhoneActionPolicy.Decision.MESSAGE,d("click","button","보내기",false,""));
  assertEquals(PhoneActionPolicy.Decision.CALL,d("click","call_button","전화 걸기",true,"general"));
  assertEquals(PhoneActionPolicy.Decision.PAYMENT,d("click","pay_button","결제하기",true,"general"));
 }
 @Test public void cannotDeclareUnknownOrRiskRoutine() {
  // REVIEW needs fresh approval in PhoneActions.act; it is never routine permission.
  assertEquals(PhoneActionPolicy.Decision.REVIEW,d("click","unknown","",false,"general"));
  assertEquals(PhoneActionPolicy.Decision.REVIEW,d("click","unknown","안내 시작",false,"general"));
  assertEquals(PhoneActionPolicy.Decision.DENY,d("click","transfer_button","송금",true,"general"));
  assertEquals(PhoneActionPolicy.Decision.DENY,d("click","permission_allow","허용",true,"general"));
  assertEquals(PhoneActionPolicy.Decision.DENY,d("click","search_button","검색 결제",true,"general"));
  assertEquals(PhoneActionPolicy.Decision.DENY,d("input_text","send_button","보내기",true,"general"));
  assertEquals(PhoneActionPolicy.Decision.DENY,d("click","search_button","검색",true,"payment"));
 }
 @Test public void protectedOrOpaqueNodesNeverGainScope() {
  for(int i=0;i<4;i++) assertEquals(PhoneActionPolicy.Decision.DENY,
   PhoneActionPolicy.decide("click","com.kakao.talk","com.kakao.talk:id/send_button","android.widget.Button","보내기",
    i==0,i==1,i==2,i==3,true,"general"));
 }
 @Test public void unrecognizedAppCannotDisguiseFinancialSendAsMessage() {
  assertEquals(PhoneActionPolicy.Decision.DENY,
   PhoneActionPolicy.decide("click","com.bank.app","com.bank.app:id/send_button","android.widget.Button","보내기",false,false,false,false,true,"general"));
 }
 private PhoneActionPolicy.Decision web(String pkg,String op,String label,boolean safe) {
  return PhoneActionPolicy.decide(op,pkg,"","android.widget.Button",label,false,false,true,false,safe,"general");
 }
 @Test public void webControlsFollowTheSameRuleInEveryApp() {
  // Many apps draw their screens in a WebView; no app gets its own gate.
  for(String pkg:new String[]{"com.skt.tmap.ku","com.nhn.android.nmap","com.example.any"}) {
   assertEquals(PhoneActionPolicy.Decision.ROUTINE,web(pkg,"click","도착",true));
   assertEquals(PhoneActionPolicy.Decision.ROUTINE,web(pkg,"click","안내 시작",true));
   assertEquals(PhoneActionPolicy.Decision.REVIEW,web(pkg,"click","도착",false));
   assertEquals(PhoneActionPolicy.Decision.DENY,web(pkg,"click","결제하기",true));
   assertEquals(PhoneActionPolicy.Decision.DENY,web(pkg,"click","로그인",true));
   assertEquals(PhoneActionPolicy.Decision.DENY,web(pkg,"input_text","검색",true));
  }
 }
 @Test public void unknownOperationDeniedEvenWithSafeFlag() {
  assertEquals(PhoneActionPolicy.Decision.DENY,d("approve","search_input","검색",true,"general"));
 }
}
