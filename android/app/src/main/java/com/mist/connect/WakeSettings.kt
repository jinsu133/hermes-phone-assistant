package com.mist.connect

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/**
 * User wake/stop phrases, sensitivity and voice-learned pronunciations.
 * Only recognized token text is stored; recordings are never kept or sent.
 */
object WakeSettings {
    const val DEFAULT_WAKE = "Hey Hermes"
    const val DEFAULT_STOP = "Stop"
    const val DEFAULT_SENSITIVITY = 50
    const val MAX_LEARNED = 6
    const val MAX_PHRASE = 40
    const val WAKE_LABEL = "WAKE"
    const val STOP_LABEL = "STOP"
    const val LEGACY_WAKE_LABEL = "HEY_HERMES"

    /** Shipped "Hey Hermes" pronunciations, including the user-approved tolerance variants. */
    val DEFAULT_WAKE_TOKENS = listOf(
        "▁HE Y ▁HER ME S",
        "▁HE Y ▁HAD ▁A ▁ME S S", "▁HE Y ▁HE AD ▁OF ▁ME S S",
        "▁HE Y ▁HE AD ▁OF ▁US", "▁HE Y ▁HAD ▁A M ▁AS")
    /** Words the on-device Korean decoder produces for the default stop word. */
    val DEFAULT_STOP_TEXT = setOf("stop", "스탑", "스톱")

    enum class Kind(val key: String) { WAKE("wake"), STOP("stop") }

    private const val PREFS = "wake_settings"
    @Volatile private var app: Context? = null
    @Volatile private var vocab: Set<String>? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun prefs(c: Context?) = (c ?: app)?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun phrase(kind: Kind, c: Context? = null): String =
        prefs(c)?.getString(kind.key, null)?.takeIf { it.isNotBlank() }
            ?: if (kind == Kind.WAKE) DEFAULT_WAKE else DEFAULT_STOP

    fun wakePhrase(c: Context? = null) = phrase(Kind.WAKE, c)
    fun stopPhrase(c: Context? = null) = phrase(Kind.STOP, c)

    fun sensitivity(c: Context? = null): Int =
        (prefs(c)?.getInt("sensitivity", DEFAULT_SENSITIVITY) ?: DEFAULT_SENSITIVITY).coerceIn(0, 100)

    /** Learned pronunciations count only for the phrase they were recorded for. */
    fun learned(kind: Kind, c: Context? = null): List<String> {
        val p = prefs(c) ?: return emptyList()
        if (p.getString("${kind.key}_learned_for", null) != phrase(kind, c)) return emptyList()
        val raw = p.getString("${kind.key}_learned", null) ?: return emptyList()
        return try { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
    }

    fun save(c: Context, wake: String, stop: String, sensitivity: Int, learnedWake: List<String>, learnedStop: List<String>) {
        prefs(c)!!.edit()
            .putString("wake", wake.trim()).putString("stop", stop.trim())
            .putInt("sensitivity", sensitivity.coerceIn(0, 100))
            .putString("wake_learned", JSONArray(learnedWake.distinct().take(MAX_LEARNED)).toString())
            .putString("wake_learned_for", wake.trim())
            .putString("stop_learned", JSONArray(learnedStop.distinct().take(MAX_LEARNED)).toString())
            .putString("stop_learned_for", stop.trim())
            .apply()
    }

    fun isDefaultWake(c: Context? = null) = wakePhrase(c).equals(DEFAULT_WAKE, ignoreCase = true)
    fun isDefaultStop(c: Context? = null) = stopPhrase(c).equals(DEFAULT_STOP, ignoreCase = true)

    /** Lower trigger threshold and higher boost make a phrase easier to fire. 50 keeps the shipped tuning. */
    fun threshold(sensitivity: Int): Float {
        val s = sensitivity.coerceIn(0, 100)
        return if (s <= 50) 0.25f - 0.15f * s / 50f else 0.10f - 0.06f * (s - 50) / 50f
    }
    fun score(sensitivity: Int): Float {
        val s = sensitivity.coerceIn(0, 100)
        return if (s <= 50) 1.0f + 0.5f * s / 50f else 1.5f + 1.0f * (s - 50) / 50f
    }

    fun vocab(context: Context): Set<String> = vocab ?: context.assets.open("sherpa-wake/tokens.txt").bufferedReader().useLines { lines ->
        lines.mapNotNull { it.trim().substringBeforeLast(' ', "").takeIf { t -> t.isNotEmpty() } }.toSet()
    }.also { vocab = it }

    /** Every token sequence that should fire for this kind with the current settings. */
    fun sequences(kind: Kind, context: Context): List<String> {
        val text = phrase(kind, context)
        val out = mutableListOf<String>()
        if (kind == Kind.WAKE && isDefaultWake(context)) out += DEFAULT_WAKE_TOKENS
        else KwsTokens.encode(text, vocab(context))?.let { out += it.joinToString(" ") }
        out += learned(kind, context)
        return out.distinct()
    }

    /** sherpa-onnx keyword lines: tokens, per-keyword boost (:), threshold (#) and label (@). */
    fun keywordSpec(sequences: List<String>, label: String, sensitivity: Int): String =
        sequences.joinToString("\n") {
            String.format(Locale.ROOT, "%s :%.2f #%.3f @%s", it, score(sensitivity), threshold(sensitivity), label)
        }

    /** Longest accepted stop utterance; longer speech that merely contains the word never stops work. */
    fun maxStopMillis(phrase: String): Int {
        val trimmed = phrase.trim()
        // Scripts written without spaces (CJK, Thai) count roughly three characters per word.
        val spaced = trimmed.contains(' ') || trimmed.all { it.code < 0x0250 }
        val words = if (spaced) trimmed.split(Regex("\\s+")).size
            else maxOf(1, (trimmed.codePointCount(0, trimmed.length) + 2) / 3)
        return minOf(4000, 1500 + 500 * (words - 1))
    }

    fun validPhrase(text: String): Boolean {
        val t = text.trim()
        return t.codePointCount(0, t.length) in 2..MAX_PHRASE && t.none { Character.isISOControl(it) }
    }

    /** Stop text as the on-device Korean decoder may write it. */
    fun stopTexts(c: Context? = null): Set<String> {
        val p = norm(stopPhrase(c))
        return if (isDefaultStop(c)) DEFAULT_STOP_TEXT + p else setOf(p)
    }

    /** Learned token line shown to the user, e.g. "▁HE Y ▁HER ME S" → "HEY HERMES". */
    fun readable(line: String): String = line.split(' ').joinToString("").replace('▁', ' ').trim()

    /** Hangul stop words are also matched by the on-device Korean decoder, so they need no training. */
    fun koreanReadable(text: String): Boolean = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HANGUL }

    fun norm(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
        .lowercase(Locale.ROOT).replace(Regex("[.!?。！？।]+$"), "").replace(Regex("\\s+"), " ")
}

/** Text to tokens for the English BPE wake model. */
object KwsTokens {
    /** Greedy longest-match over the model's pieces; null when the text has no Latin spelling. */
    @JvmStatic fun encode(text: String, vocab: Set<String>): List<String>? {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .uppercase(Locale.ROOT).replace('’', '\'')
        if (folded.any { it.isLetter() && it !in 'A'..'Z' }) return null
        val words = folded.replace(Regex("[^A-Z' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = mutableListOf<String>()
        for (word in words) {
            var i = 0
            var first = true
            while (i < word.length) {
                var piece: String? = null
                var j = word.length
                while (j > i) {
                    val candidate = (if (first) "▁" else "") + word.substring(i, j)
                    if (candidate in vocab) { piece = candidate; break }
                    j--
                }
                if (piece == null) {
                    if (first && "▁" in vocab) { out += "▁"; first = false; continue }
                    return null
                }
                out += piece
                i = j
                first = false
            }
        }
        return out
    }

    /** Recognizer output to a storable keyword line, or null when too short to be distinctive. */
    @JvmStatic fun learnedLine(tokens: List<String>, minTokens: Int, vocab: Set<String>): String? {
        val kept = tokens.map { it.trim() }.filter { it.isNotEmpty() && it in vocab }
        if (kept.size < minTokens || kept.size > 24 || !kept.first().startsWith("▁")) return null
        return kept.joinToString(" ")
    }
}
