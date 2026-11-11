package com.mist.connect;
/** Exact local stop command; normal sentences containing the word do not stop work. */
public final class StopPhraseGate {
    private int matches;
    public boolean accept(String text, boolean complete) {
        String t = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[.!?]+$", "");
        boolean exact = t.equals("스탑") || t.equals("스톱") || t.equals("stop");
        matches = exact ? matches + 1 : 0;
        return exact && complete;
    }
    public void reset() { matches = 0; }
}
