package com.mist.connect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fixed no-file/no-network compute consent. Never carries approval flags. */
public final class HostCodeExecution {
    public static final String PHRASE="이 코드 격리 실행 허용";
    public final String request,name,source,digest;
    private static String sha(String text) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            return s.toString();
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public HostCodeExecution(String request,String name,String source,String sourceDigest,String actionDigest) {
        if(request==null || !request.matches("[A-Za-z0-9_-]{1,80}") || name==null ||
            !name.matches("[A-Za-z0-9가-힣][A-Za-z0-9가-힣 _-]{0,70}\\.py") || source==null ||
            source.isEmpty() || source.codePointCount(0,source.length())>2000 || !sha(source).equals(sourceDigest))
            throw new IllegalArgumentException("invalid_code_execution");
        if(source.codePoints().anyMatch(c -> c!='\n' && c!='\r' && c!='\t' &&
            (Character.isISOControl(c) || Character.getType(c)==Character.FORMAT ||
            Character.getType(c)==Character.SURROGATE || Character.getType(c)==Character.PRIVATE_USE ||
            Character.getType(c)==Character.UNASSIGNED)))throw new IllegalArgumentException("invalid_code_execution");
        this.request=request;this.name=name;this.source=source;
        digest=sha(String.join("\u0000",request,name,sourceDigest));
        if(!digest.equals(actionDigest))throw new IllegalArgumentException("code_digest_mismatch");
    }
    public String prompt() {
        return "코드 " + name + "을 별도 계산 프로세스에서 실행하려고 합니다. 파일 접근, 인터넷 연결, 다른 프로그램 실행은 차단됩니다. " +
            "실행할 코드 원문은 다음과 같습니다.\n" + source +
            "\n이 코드만 실행하려면 ‘" + PHRASE + "’이라고 말씀해 주세요.";
    }
}
