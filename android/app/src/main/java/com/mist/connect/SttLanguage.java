package com.mist.connect;

import java.util.List;
import java.util.Locale;

/**
 * Picks the on-device recognizer language. Speech, including spoken approval phrases,
 * is recognized in the app's display language; the host's stt_language setting has no say.
 */
public final class SttLanguage {
    public static final String FALLBACK = "ko-KR";

    private SttLanguage() {}

    /** Exact installed tag, else one with the same primary language, else null. */
    public static String pick(List<String> installed, String preferred) {
        if (installed == null || preferred == null || preferred.isEmpty()) return null;
        for (String tag : installed) if (tag != null && tag.equalsIgnoreCase(preferred)) return tag;
        String language = primary(preferred);
        for (String tag : installed) if (tag != null && primary(tag).equals(language)) return tag;
        return null;
    }

    static String primary(String tag) {
        int dash = tag.replace('_', '-').indexOf('-');
        return (dash < 0 ? tag : tag.substring(0, dash)).toLowerCase(Locale.ROOT);
    }
}
