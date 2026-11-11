package com.mist.connect;

import java.util.Map;
import java.util.regex.Pattern;

/** Conservative preview policy; missing financial/contact details fail closed. */
public final class ConfirmationPolicy {
    public static boolean requiresApproval(String action) {
        return !(action.equals("home") || action.equals("back") || action.equals("read_node") || action.equals("open_app") || action.equals("list_apps"));
    }
    /** Narrow everyday operations; unknown targets remain approval-gated. */
    public static boolean isEveryday(String action, String pkg, String target, String kind) {
        if (!kind.isEmpty() && !kind.equals("general")) return false;
        if (action.equals("scroll_forward") || action.equals("scroll_backward")) return true;
        if (!pkg.equals("com.skt.tmap.ku")) return false;
        if (Pattern.compile("결제|송금|구매|주문|예약|삭제|전송|보내|전화|통화|구독|동의|권한|로그인|비밀번호|인증", Pattern.CASE_INSENSITIVE).matcher(target).find()) return false;
        String label = target.trim().replaceAll("\\s+", " ");
        if (action.equals("input_text")) return label.matches(".*(검색|search).*" );
        if (!action.equals("click")) return false;
        return label.matches("장소, 주소, 맛집, 메뉴 검색|검색|목적지 검색|길찾기|길안내|길 안내|안내시작|안내 시작|경로 보기|경로보기|티맵추천|내비");
    }
    private static final Pattern MONEY=Pattern.compile("결제|송금|구매|주문|이체|구독|pay|purchase|checkout|transfer|subscribe",Pattern.CASE_INSENSITIVE);
    private static final Pattern SEND=Pattern.compile("보내|전송|send",Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL=Pattern.compile("전화|통화|call|dial",Pattern.CASE_INSENSITIVE);
    private static String field(Map<String,String> d,String key,String screen) {
        String s=d.get(key);
        if(s==null || s.trim().isEmpty() || s.length()>2000 || !screen.contains(s))
            throw new IllegalArgumentException("confirmation_details_missing_or_not_on_screen:"+key);
        return s;
    }
    public static String prompt(String op,String target,String screen,Map<String,String> d,String input) {
        String kind=d.getOrDefault("kind", "general");
        String message;
        if (kind.equals("payment") || (op.equals("click") && MONEY.matcher(target).find())) {
            String merchant=field(d,"merchant",screen), amount=field(d,"amount",screen), method=field(d,"method",screen);
            message="결제 관련 작업을 실행하려고 합니다.\n대상: "+merchant+"\n최종 금액: "+amount+"\n결제 수단: "+method+"\n이 내용으로 진행할까요?\n이전 작업 승인은 결제 승인으로 사용하지 않습니다.";
        } else if (kind.equals("message") || (op.equals("click") && SEND.matcher(target).find())) {
            message=field(d,"recipient",screen)+"에게 문자/카카오톡 메시지를 보내려고 합니다.\n내용:\n"+field(d,"content",screen)+"\n이 내용으로 정말 보낼까요?";
        } else if (kind.equals("call") || (op.equals("click") && CALL.matcher(target).find())) {
            message=field(d,"recipient",screen)+"에게 전화 걸까요?";
        } else {
            String action=Map.of("home","홈 화면으로 이동","back","뒤로 이동","recents","최근 앱 목록 열기","notifications","알림창 열기","click","누르기","input_text","글자 입력","scroll_forward","아래로 스크롤","scroll_backward","위로 스크롤").getOrDefault(op,op);
            message="휴대폰에서 다음 작업을 실행할까요?\n동작: "+action+"\n대상: "+target;
            if(op.equals("input_text")) message+="\n입력할 내용:\n"+input;
        }
        return message+"\n\n이번 작업 한 번만 승인됩니다. 화면/내용 변경, 잠금, 중지, 취소 또는 2분 경과 시 실행하지 않습니다.";
    }
}
