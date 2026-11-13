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
    static String t(int id, String ko, Object... args) {
        return AppText.INSTANCE.get(id, args.length == 0 ? ko : String.format(java.util.Locale.ROOT, ko, args), args);
    }
    public static String body(String op,String target,String screen,Map<String,String> d,String input) {
        String kind=d.getOrDefault("kind", "general");
        if (kind.equals("payment") || (op.equals("click") && MONEY.matcher(target).find())) {
            String merchant=field(d,"merchant",screen), amount=field(d,"amount",screen), method=field(d,"method",screen);
            return t(R.string.cf_payment,"결제 관련 작업을 실행하려고 합니다.\n대상: %1$s\n최종 금액: %2$s\n결제 수단: %3$s\n이 내용으로 진행할까요?\n이전 작업 승인은 결제 승인으로 사용하지 않습니다.",merchant,amount,method);
        } else if (kind.equals("message") || (op.equals("click") && SEND.matcher(target).find())) {
            String recipient=field(d,"recipient",screen), content=field(d,"content",screen);
            return t(R.string.cf_message,"%1$s에게 문자/카카오톡 메시지를 보내려고 합니다.\n내용:\n%2$s\n이 내용으로 정말 보낼까요?",recipient,content);
        } else if (kind.equals("call") || (op.equals("click") && CALL.matcher(target).find())) {
            return t(R.string.cf_call,"%1$s에게 전화 걸까요?",field(d,"recipient",screen));
        }
        String message=t(R.string.cf_general,"휴대폰에서 다음 작업을 실행할까요?\n동작: %1$s\n대상: %2$s",opLabel(op),target);
        if(op.equals("input_text")) message+="\n"+t(R.string.cf_input,"입력할 내용:\n%1$s",input);
        return message;
    }
    static String opLabel(String op) {
        switch (op) {
            case "home": return t(R.string.cf_op_home,"홈 화면으로 이동");
            case "back": return t(R.string.cf_op_back,"뒤로 이동");
            case "recents": return t(R.string.cf_op_recents,"최근 앱 목록 열기");
            case "notifications": return t(R.string.cf_op_notifications,"알림창 열기");
            case "click": return t(R.string.cf_op_click,"누르기");
            case "input_text": return t(R.string.cf_op_input_text,"글자 입력");
            case "scroll_forward": return t(R.string.cf_op_scroll_forward,"아래로 스크롤");
            case "scroll_backward": return t(R.string.cf_op_scroll_backward,"위로 스크롤");
            default: return op;
        }
    }
    public static String onceNotice() {
        return t(R.string.cf_once,"이번 작업 한 번만 승인됩니다. 화면/내용 변경, 잠금, 중지, 취소 또는 2분 경과 시 실행하지 않습니다.");
    }
    public static String screenHeader() { return t(R.string.cf_screen_header,"기기 화면 원문:"); }
    public static String sayPhrase(String phrase) {
        return t(R.string.cf_say_phrase,"허용하려면 ‘%1$s’이라고 말씀해 주세요. 다른 응답이나 무응답에는 실행하지 않습니다.",phrase);
    }
    public static String prompt(String op,String target,String screen,Map<String,String> d,String input) {
        return body(op,target,screen,d,input)+"\n\n"+onceNotice();
    }
}
