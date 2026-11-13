package com.mist.connect;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Executed-node policy, the same for every app. Unknown-but-safe nodes are approval-gated;
 * risky ones stay denied. */
public final class PhoneActionPolicy {
    public enum Decision { ROUTINE, REVIEW, PAYMENT, MESSAGE, CALL, DENY }
    private static final Set<String> GLOBAL=Set.of("home","back","recents","notifications","open_app","list_apps","read_node","package_version");
    private static final Set<String> MESSAGING=Set.of("com.kakao.talk","com.google.android.apps.messaging","com.samsung.android.messaging","org.telegram.messenger","com.whatsapp","org.thoughtcrime.securesms");
    private static final Set<String> DIALERS=Set.of("com.android.dialer","com.google.android.dialer","com.samsung.android.dialer");
    private static final Set<String> NODE=Set.of("click","input_text","submit_search","scroll_forward","scroll_backward");
    private static final Pattern DIRECT=Pattern.compile("송금|이체|매수|매도|transfer|trade|permission|grant|권한|보안|인증|비밀번호|password|otp|pin_code|login|로그인|동의|agree|delete|삭제",Pattern.CASE_INSENSITIVE);
    private static final Pattern RISK=Pattern.compile("결제|구매|주문|예약|전송|보내|전화|통화|구독|pay|buy|purchase|checkout|subscribe|send|call|dial",Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_ID=Pattern.compile("send|send_button|button_send|btn_send|send_message|sendmessage|message_send|send_btn");
    private static final Pattern CALL_ID=Pattern.compile("call|call_button|button_call|btn_call|dial|dial_button|dialpad_floating_action_button");
    private static final Pattern PAYMENT_ID=Pattern.compile("pay|pay_button|button_pay|btn_pay|purchase|purchase_button|checkout|checkout_button|confirm_payment");

    /** Only own ancestor metadata, never flattened destination/sibling text. */
    public static boolean riskyNavigationContext(String value) {
        return DIRECT.matcher(value).find() || RISK.matcher(value).find() ||
            Pattern.compile("공유|share|redirect",Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    public static Decision decide(String op,String pkg,String resource,String clazz,String label,
            boolean editable,boolean checkable,boolean web,boolean sensitive,boolean structuralSafe,String kind) {
        if(GLOBAL.contains(op)) return Decision.ROUTINE;
        if(!NODE.contains(op) || sensitive || checkable) return Decision.DENY;
        String id=resource==null?"":resource.substring(resource.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
        String text=label==null?"":label.trim().replaceAll("\\s+"," ");
        // Web content (many apps draw screens in a WebView) is judged like native content, in
        // every app: a structurally checked, labelled browsing control runs; anything else is
        // approved per click. Typing into web forms and final effects there stay refused.
        if(web) {
            if(!op.equals("click")) return Decision.DENY;
            if(DIRECT.matcher(id+" "+text).find() || RISK.matcher(id+" "+text).find() ||
                (kind!=null && !kind.isEmpty() && !kind.equals("general"))) return Decision.DENY;
            return structuralSafe ? Decision.ROUTINE : Decision.REVIEW;
        }
        if(DIRECT.matcher(id+(editable?"":" "+text)).find()) return Decision.DENY;
        // A structurally identified composer/search input is preparation, not final send.
        if(editable && op.equals("input_text") && structuralSafe && !RISK.matcher(id).find() &&
                (kind==null || kind.isEmpty() || kind.equals("general"))) return Decision.ROUTINE;
        // Final-effect classification outranks any ordinary structural role or model 'general'.
        boolean nativeButton=op.equals("click") && !editable && pkg!=null && !pkg.isEmpty() &&
            resource!=null && resource.startsWith(pkg+":id/") && clazz!=null &&
            clazz.matches(".*(Button|TextView|ImageView|ViewGroup|LinearLayout|FrameLayout|RelativeLayout)$");
        Decision effect=Decision.DENY;
        if(nativeButton) {
            if(MESSAGE_ID.matcher(id).matches() || text.matches("보내기|보내|전송|메시지 보내기|문자 보내기|(?i:send|send message)")) effect=Decision.MESSAGE;
            else if(CALL_ID.matcher(id).matches() || text.matches("전화 걸기|전화걸기|통화|전화|(?i:call|dial)")) effect=Decision.CALL;
            else if(PAYMENT_ID.matcher(id).matches() || text.matches("결제|결제하기|구매하기|주문하기|구독하기|(?i:pay|purchase|checkout|subscribe)")) effect=Decision.PAYMENT;
        }
        if(effect==Decision.MESSAGE && Pattern.compile("결제|구매|주문|예약|구독|전화|통화|pay|purchase|checkout|subscribe|call|dial",Pattern.CASE_INSENSITIVE).matcher(text).find()) return Decision.DENY;
        if(effect==Decision.CALL && Pattern.compile("결제|구매|주문|예약|구독|보내|전송|pay|purchase|checkout|subscribe|send",Pattern.CASE_INSENSITIVE).matcher(text).find()) return Decision.DENY;
        if(effect==Decision.PAYMENT && Pattern.compile("전화|통화|보내|전송|call|dial|send",Pattern.CASE_INSENSITIVE).matcher(text).find()) return Decision.DENY;
        if(effect==Decision.MESSAGE && !MESSAGING.contains(pkg)) return Decision.DENY;
        if(effect==Decision.CALL && !DIALERS.contains(pkg) && !MESSAGING.contains(pkg)) return Decision.DENY;
        if(effect!=Decision.DENY) {
            String expected=effect.name().toLowerCase(Locale.ROOT);
            return kind==null || kind.isEmpty() || kind.equals("general") || kind.equals(expected) ? effect : Decision.DENY;
        }
        if(RISK.matcher(id+" "+text).find() || (kind!=null && !kind.isEmpty() && !kind.equals("general"))) return Decision.DENY;
        return structuralSafe ? Decision.ROUTINE : Decision.REVIEW;
    }
}
