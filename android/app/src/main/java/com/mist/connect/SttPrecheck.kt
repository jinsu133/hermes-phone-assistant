package com.mist.connect

import org.json.JSONObject
import java.security.MessageDigest

/** Follow-up audio is transcribed on the phone before it is uploaded, so ambient sound that
 * yields no words keeps the window open instead of becoming a failed request. The plugin then
 * asks this same app (/stt/ko) for the transcript of the exact same PCM; one remembered
 * result for those bytes, briefly, avoids decoding it twice. Never persisted or logged. */
internal object SttPrecheck {
    private const val TTL_MS = 60_000L
    private class Entry(val digest: String, val result: String, val at: Long)
    private var entry: Entry? = null

    private fun digest(pcm: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }

    /** Words were heard: true. No words (no match / empty): false. Anything else: null. */
    fun verdict(result: JSONObject): Boolean? = when {
        result.optBoolean("success") && result.optString("transcript").isNotBlank() -> true
        result.optBoolean("success") -> false
        result.optString("error") in setOf("stt_no_match", "stt_empty_final", "stt_speech_timeout") -> false
        else -> null
    }

    fun remember(pcm: ByteArray, result: JSONObject, now: Long) = rememberRaw(pcm, result.toString(), now)
    fun take(pcm: ByteArray, now: Long): JSONObject? =
        takeRaw(pcm, now)?.let { JSONObject(it).put("stt_precheck_reused", true) }

    @Synchronized internal fun rememberRaw(pcm: ByteArray, result: String, now: Long) {
        entry = Entry(digest(pcm), result, now)
    }

    /** One use: the remembered result for exactly these bytes, if still fresh. */
    @Synchronized internal fun takeRaw(pcm: ByteArray, now: Long): String? {
        val e = entry ?: return null
        entry = null
        if (now - e.at !in 0..TTL_MS || e.digest != digest(pcm)) return null
        return e.result
    }

    @Synchronized fun clear() { entry = null }
}
