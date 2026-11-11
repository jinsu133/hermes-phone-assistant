package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class EverydayPolicyTest {
 @Test public void tmapSearchAndGuidance() {
  assertTrue(ConfirmationPolicy.isEveryday("click","com.skt.tmap.ku","장소, 주소, 맛집, 메뉴 검색","general"));
  assertTrue(ConfirmationPolicy.isEveryday("click","com.skt.tmap.ku","안내 시작",""));
  assertTrue(ConfirmationPolicy.isEveryday("click","com.skt.tmap.ku","길찾기",""));
  assertFalse(ConfirmationPolicy.isEveryday("click","com.skt.tmap.ku","길찾기 결제",""));
  assertTrue(ConfirmationPolicy.isEveryday("input_text","com.skt.tmap.ku","목적지 검색",""));
 }
 @Test public void dangerousAndUnknownRemainGated() {
  for(String s:new String[]{"결제","예약 확정","전화","전송","삭제","검색 후 결제","동의","","경복궁"})
   assertFalse(s,ConfirmationPolicy.isEveryday("click","com.skt.tmap.ku",s,"general"));
  assertFalse(ConfirmationPolicy.isEveryday("click","com.bank.app","검색",""));
  assertFalse(ConfirmationPolicy.isEveryday("click","com.skt.tmap.ku","검색","payment"));
  assertFalse(ConfirmationPolicy.isEveryday("input_text","com.skt.tmap.ku","비밀번호 검색",""));
 }
 @Test public void ordinaryScroll() {
  assertTrue(ConfirmationPolicy.isEveryday("scroll_forward","com.example","","general"));
  assertFalse(ConfirmationPolicy.isEveryday("scroll_forward","com.example","","payment"));
 }
}
