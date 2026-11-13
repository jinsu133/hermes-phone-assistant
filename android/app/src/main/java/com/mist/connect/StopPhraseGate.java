package com.mist.connect;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Exact local stop command; normal sentences containing the word do not stop work. */
public final class StopPhraseGate {
    private final Set<String> phrases;
    private int matches;

    public StopPhraseGate() { this(new HashSet<>(Arrays.asList("스탑", "스톱", "stop"))); }

    /** {@code phrases} are the user's stop words, already normalized like {@link #norm}. */
    public StopPhraseGate(Set<String> phrases) {
        Set<String> copy = new HashSet<>();
        for (String p : phrases) { String n = norm(p); if (!n.isEmpty()) copy.add(n); }
        this.phrases = copy;
    }

    public boolean accept(String text, boolean complete) {
        boolean exact = phrases.contains(norm(text));
        matches = exact ? matches + 1 : 0;
        return exact && complete;
    }

    static String norm(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT)
            .replaceAll("[.!?。！？।]+$", "").replaceAll("\\s+", " ");
    }

    public void reset() { matches = 0; }
}
