package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

public class ActionConfirmationTest {
 @Test public void noApprovalAndSingleUse() {
  ActionApprovalGate g=new ActionApprovalGate("a",1000);
  assertFalse(g.consume("a",100,true)); g.userDecision(true,100);
  assertFalse(g.consume("b",101,true)); assertTrue(g.consume("a",102,true)); assertFalse(g.consume("a",103,true));
 }
 @Test public void cancelledExpiredAndChanged() {
  ActionApprovalGate g=new ActionApprovalGate("a",1000); g.userDecision(true,100);
  assertFalse(g.consume("a",101,false)); g.cancel("a"); assertFalse(g.consume("a",102,true));
  ActionApprovalGate e=new ActionApprovalGate("e",1000); e.userDecision(true,100); assertFalse(e.consume("e",1000,true));
 }
 @Test public void paymentAlwaysGetsSeparateDetailsPrompt() {
  String screen="상점A 결제 12,000원 카드A";
  String p=ConfirmationPolicy.prompt("click","결제",screen,Map.of("merchant","상점A","amount","12,000원","method","카드A"),"");
  assertTrue(p.contains("12,000원")); assertTrue(p.contains("카드A")); assertTrue(p.contains("진행할까요"));
 }
 @Test(expected=IllegalArgumentException.class) public void paymentMissingAmountDenied() {
  ConfirmationPolicy.prompt("click","결제","상점A 결제",Map.of("merchant","상점A"),"");
 }
 @Test(expected=IllegalArgumentException.class) public void changedPaymentDenied() {
  ConfirmationPolicy.prompt("click","결제","상점A 결제 20,000원 카드A",Map.of("merchant","상점A","amount","12,000원","method","카드A"),"");
 }
 @Test public void contactPreviewsAndFullContent() {
  String screen="홍길동 안녕하세요. 오후에 뵙겠습니다.";
  String p=ConfirmationPolicy.prompt("click","보내기",screen,Map.of("recipient","홍길동","content","안녕하세요. 오후에 뵙겠습니다."),"");
  assertTrue(p.contains("홍길동")); assertTrue(p.contains("안녕하세요. 오후에 뵙겠습니다.")); assertTrue(p.contains("정말 보낼까요"));
  assertTrue(ConfirmationPolicy.prompt("click","전화",screen,Map.of("recipient","홍길동"),"").contains("홍길동에게 전화 걸까요?"));
 }
 @Test public void homeAndLaunchAreExemptButRiskyOperationsRemainGated() {
  assertFalse(ConfirmationPolicy.requiresApproval("home"));
  assertFalse(ConfirmationPolicy.requiresApproval("open_app"));
  assertTrue(ConfirmationPolicy.requiresApproval("click"));
  assertTrue(ConfirmationPolicy.requiresApproval("input_text"));
  assertFalse(ConfirmationPolicy.requiresApproval("back"));
 }
 @Test public void genericOperationsStillAsk() {
  assertTrue(ConfirmationPolicy.prompt("home","home","화면",Map.of(),"").contains("실행할까요"));
 }
}
