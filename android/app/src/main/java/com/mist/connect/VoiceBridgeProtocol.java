package com.mist.connect;

import java.nio.charset.StandardCharsets;

/** Fixed-loopback voice bridge framing and input validation. */
public final class VoiceBridgeProtocol {
    public static final String BASE_URL = "http://127.0.0.1:5001";
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_HTTP_BODY = 5 * 1024 * 1024;
    public static final int MAX_REPLY_AUDIO = 8 * 1024 * 1024;

    private VoiceBridgeProtocol() { }

    public static byte[] toWav(byte[] pcm16Mono) {
        if (pcm16Mono == null || pcm16Mono.length == 0 || (pcm16Mono.length & 1) != 0) {
            throw new IllegalArgumentException("PCM must be nonempty, 16-bit mono data");
        }
        if (pcm16Mono.length > MAX_HTTP_BODY - 44) {
            throw new IllegalArgumentException("PCM exceeds the request limit");
        }
        byte[] wav = new byte[44 + pcm16Mono.length];
        putAscii(wav, 0, "RIFF");
        putIntLE(wav, 4, 36 + pcm16Mono.length);
        putAscii(wav, 8, "WAVE");
        putAscii(wav, 12, "fmt ");
        putIntLE(wav, 16, 16);
        putShortLE(wav, 20, 1);
        putShortLE(wav, 22, 1);
        putIntLE(wav, 24, SAMPLE_RATE);
        putIntLE(wav, 28, SAMPLE_RATE * 2);
        putShortLE(wav, 32, 2);
        putShortLE(wav, 34, 16);
        putAscii(wav, 36, "data");
        putIntLE(wav, 40, pcm16Mono.length);
        System.arraycopy(pcm16Mono, 0, wav, 44, pcm16Mono.length);
        return wav;
    }

    public static boolean validToken(String token) {
        if (token == null || token.length() < 32 || token.length() > 512) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9') && c != '-' && c != '_') return false;
        }
        return true;
    }

    public static String replyUrl(String requestId) {
        if (!validRequestId(requestId)) return null;
        return BASE_URL + "/reply?request_id=" + requestId;
    }

    public static String ttsUrl(String requestId) {
        if (!validRequestId(requestId)) return null;
        return BASE_URL + "/tts?request_id=" + requestId;
    }

    public static boolean validRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 80) return false;
        for (int i = 0; i < requestId.length(); i++) {
            char c = requestId.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9') && c != '-' && c != '_') return false;
        }
        return true;
    }

    private static void putAscii(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void putShortLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private static void putIntLE(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >>> 8) & 0xff);
        target[offset + 2] = (byte) ((value >>> 16) & 0xff);
        target[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }
}
