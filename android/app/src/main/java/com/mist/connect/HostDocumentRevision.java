package com.mist.connect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fixed revision-copy consent. No file path, shell command or approval input. */
public final class HostDocumentRevision {
    public final String request, name, before, after, oldText, newText, digest;
    public static String phrase() { return ConfirmationPolicy.t(R.string.vp_document, "이 문서 수정본 저장 허용"); }
    public HostDocumentRevision(String request, String name, String before, String after,
                                String oldText, String newText, String claimedDigest) {
        if (request == null || !request.matches("[A-Za-z0-9_-]{1,80}") || name == null ||
                !name.matches("[A-Za-z0-9가-힣][A-Za-z0-9가-힣 _-]{0,70}") ||
                before == null || !before.matches("[a-f0-9]{64}") ||
                after == null || !after.matches("[a-f0-9]{64}") ||
                !textValid(oldText, false) || !textValid(newText, true) || oldText.equals(newText))
            throw new IllegalArgumentException("invalid_document_revision");
        this.request=request; this.name=name; this.before=before; this.after=after;
        this.oldText=oldText; this.newText=newText;
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(
                String.join("\u0000",request,name,before,after,oldText,newText).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();
            for(byte b:bytes) hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            digest=hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        if(!digest.equals(claimedDigest)) throw new IllegalArgumentException("revision_digest_mismatch");
    }
    private static boolean textValid(String text, boolean emptyAllowed) {
        if(text==null || (!emptyAllowed && text.isEmpty()) || text.codePointCount(0,text.length())>160) return false;
        return text.codePoints().allMatch(c -> c=='\n' || c=='\r' || c=='\t' ||
                (!Character.isISOControl(c) && Character.getType(c)!=Character.FORMAT &&
                 Character.getType(c)!=Character.SURROGATE && Character.getType(c)!=Character.PRIVATE_USE &&
                 Character.getType(c)!=Character.UNASSIGNED));
    }
    public String prompt() {
        String change = newText.isEmpty() ? ConfirmationPolicy.t(R.string.cf_document_delete, "해당 부분 삭제") : newText;
        return ConfirmationPolicy.t(R.string.cf_document, "문서 %1$s에서 다음 내용을 바꾸려고 합니다.\n기존 내용: %2$s\n수정 내용: %3$s" +
                "\n원본은 그대로 두고 Hermes의 이번 요청 전용 공간에 새 수정본을 저장합니다. 저장하려면 ‘%4$s’이라고 말씀해 주세요.",
                name, oldText, change, phrase());
    }
}
