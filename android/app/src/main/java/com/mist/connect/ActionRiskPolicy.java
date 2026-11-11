package com.mist.connect;

import java.util.Locale;
import java.util.regex.Pattern;

/** Native structural search roles, not an app-name/text-only approval bypass. */
public final class ActionRiskPolicy {
    private static final Pattern RISK = Pattern.compile("결제|송금|이체|구매|주문|예약|삭제|전송|보내|전화|통화|구독|동의|권한|비밀번호|인증|로그인|checkout|delete|send|purchase|subscribe",Pattern.CASE_INSENSITIVE);
    /** Structural search evidence: native shape/label first, known resource IDs second.
     * An app-specific ID list alone made every new app or UI revision unsupported. */
    public static boolean nativeSearch(String action,String resource,String clazz,String label,boolean editable,boolean web,String input) {
        if(web || clazz==null) return false;
        String text=label==null?"":label;
        if(RISK.matcher(text).find()) return false;
        String raw=resource==null?"":resource;
        String id=raw.substring(raw.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
        boolean searchField=editable && clazz.endsWith("EditText") && id.matches("search_src_text|search_box_text|search_text|search_input|search_edit_text|search_edit|et_search|edit_search|url_bar");
        boolean searchOpen=clazz.matches(".{0,64}(EditText|TextView|ImageView|ImageButton|Button|LinearLayout|FrameLayout|ViewGroup)$")
            && id.matches("search_box|search_box_text|search_button|search_button_icon|search_mag_icon|search_bar|search_toolbar|search_text|search_src_text|url_bar");
        // Structural fallback: a native control whose own label is a search affordance.
        if(!searchField && editable && clazz.endsWith("EditText") && SEARCH_WORD.matcher(text).find()) searchField=true;
        if(!searchOpen && !editable && text.length()<=30 && SEARCH_WORD.matcher(text).find() &&
            clazz.matches(".{0,64}(TextView|ImageView|ImageButton|Button|LinearLayout|FrameLayout|ViewGroup)$")) searchOpen=true;
        if(action.equals("input_text") || action.equals("submit_search")) return searchField && safeQuery(input);
        return action.equals("click") && (searchField || searchOpen);
    }
    private static final Pattern SEARCH_WORD=Pattern.compile("검색|search",Pattern.CASE_INSENSITIVE);
    /** Evidence comes from native accessibility structure, never caller-supplied roles.
     * This bounded adapter recognizes common messaging/search list structures only.
     * It is NOT permission to click arbitrary list items or WebView links. */
    public static String nativeListRole(String resource,String clazz,boolean clickable,
            boolean checkable,boolean web,boolean listParent,String parentId,java.util.Set<String> children) {
        if(!clickable || checkable || web || !listParent || resource==null || clazz==null) return "";
        String id=resource.substring(resource.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
        if(RISK.matcher(id).find() || id.matches(".*(pay|call|dial|remove|buy|confirm|agree|allow|toggle|switch).*")) return "";
        if(!clazz.matches(".*(Button|ViewGroup|LinearLayout|FrameLayout|RelativeLayout)$")) return "";
        java.util.Set<String> ids=new java.util.HashSet<>();
        for(String child:children) ids.add(child.toLowerCase(Locale.ROOT));
        boolean preview=ids.stream().anyMatch(s->s.matches("message|lastmessage|last_message|message_preview|conversation_snippet"));
        boolean time=ids.stream().anyMatch(s->s.matches("time|timestamp|message_time|date"));
        boolean identity=ids.stream().anyMatch(s->s.matches("name|title|conversation_name|unread_count"));
        boolean folder=id.equals("container") && ids.contains("lastmessage") && ids.contains("time");
        if(preview && time && (identity || folder)) return "conversation_row";
        String parent=parentId.substring(parentId.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
        if(parent.matches("omnibox_suggestions_dropdown|search_suggestions|suggestion_list|suggestions_list|search_suggest_list")
                && ids.stream().anyMatch(s->s.matches("line_1|suggestion_text|query_text"))) return "search_suggestion";
        return "";
    }
    public static boolean safeQuery(String value) {
        if(value==null || value.length()>2000 || value.contains("\n") || value.contains("\r")) return false;
        String query=value.trim();
        return !query.matches("(?is)^[a-z][a-z0-9+.-]*:.*") || query.matches("(?is)^https?://.*");
    }
}
