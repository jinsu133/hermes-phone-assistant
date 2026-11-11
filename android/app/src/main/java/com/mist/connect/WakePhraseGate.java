package com.mist.connect;
import java.text.Normalizer;
/** A wake match is a trigger candidate, never speaker identity or action approval. */
public final class WakePhraseGate {
    public static final String PHRASE="헤르메스";
    // Accept only the selected Korean phrase or its English spelling at the start.
    private static final java.util.regex.Pattern PREFIX=java.util.regex.Pattern.compile(
        "^(?:헤르메스|hermes)(?:\\s|[,!?，。]|$)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private int stable;
    private boolean fired;
    public boolean accept(String text, boolean endOfUtterance) {
        String clean=Normalizer.normalize(text,Normalizer.Form.NFC).trim().replaceAll("\\s+"," ");
        boolean match=PREFIX.matcher(clean).find();
        stable=match?stable+1:0;
        boolean hit=!fired && match && (endOfUtterance||stable>=2);
        if(hit)fired=true;
        if(endOfUtterance)reset();
        return hit;
    }
    public void reset() {stable=0;fired=false;}
}
