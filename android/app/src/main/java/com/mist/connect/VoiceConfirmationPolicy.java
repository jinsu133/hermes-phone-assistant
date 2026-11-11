package com.mist.connect;

/** A fresh, exact utterance approves one action; this is not speaker authentication. */
public final class VoiceConfirmationPolicy {
    private VoiceConfirmationPolicy() {}
    public static String phrase(String kind) {
        switch (kind) {
            case "message": return "이 메시지 보내기 허용";
            case "call": return "이 전화 걸기 허용";
            case "payment": return "이 구매 허용";
            default: return "이 작업 실행 허용";
        }
    }
    public static boolean matches(String expected, String heard) {
        if (expected == null || heard == null || expected.isEmpty()) return false;
        return expected.replaceAll("\\s+", "").equals(heard.trim().replaceAll("[.!?。！？]+$", "").replaceAll("\\s+", ""));
    }
    public static String errorCode(String message) {
        if (message == null) return "ACTION_DENIED_OR_STALE_SCREEN";
        if (message.startsWith("confirmation_details_missing") || message.equals("complete_message_body_required")) return "CONFIRMATION_DETAILS_REQUIRED";
        switch (message) {
            case "stale_snapshot": return "SNAPSHOT_EXPIRED";
            case "node_changed": case "unknown_ref": return "TARGET_CHANGED";
            case "foreground_changed": return "FOREGROUND_CHANGED";
            case "protected_input_on_screen": return "PROTECTED_INPUT";
            case "screen_too_large_for_confirmation": return "SCREEN_LIMIT_EXCEEDED";
            case "screen_unavailable": return "SCREEN_UNAVAILABLE";
            case "confirmation_pending": return "APPROVAL_PENDING";
            case "control_stopped": return "CONTROL_STOPPED";
            case "voice_owner_required": return "VOICE_OWNER_REQUIRED";
            case "user_direct_action_required": return "USER_DIRECT_ACTION_REQUIRED";
            case "target_not_clickable": return "UNSUPPORTED_UI";
            case "unsupported_ui": case "unsupported_action": return "UNSUPPORTED_UI";
            default: return "ACTION_DENIED_OR_STALE_SCREEN";
        }
    }
}
