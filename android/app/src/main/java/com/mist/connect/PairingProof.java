package com.mist.connect;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** A fresh transport-check proof, never an unlock/action approval. */
public final class PairingProof {
    private PairingProof() {}
    public static String sign(String key, String nonce, String pairing, long revision, String instance) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{32,512}") ||
            nonce == null || !nonce.matches("[a-f0-9]{64}") || revision < 1 ||
            !canonicalUuid(pairing) || !canonicalUuid(instance))
            throw new IllegalArgumentException("pairing_proof_invalid");
        String text="hermes-phone-pairing-check-v1\n"+nonce+"\n"+pairing+"\n"+revision+"\n"+instance;
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));
            byte[] digest=mac.doFinal(text.getBytes(StandardCharsets.US_ASCII));
            StringBuilder output=new StringBuilder(64);
            for (byte b : digest) output.append(String.format(java.util.Locale.ROOT,"%02x", b & 255));
            return output.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("pairing_proof_unavailable");
        }
    }
    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }
}
