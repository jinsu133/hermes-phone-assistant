package com.mist.connect

import java.util.concurrent.atomic.AtomicReference

/** Debug test harness slot: one recorded utterance handed to the wake-wait capture as if it
 * were spoken after the wake word. Inert in release builds (nothing can offer, take is null). */
internal object InjectedUtterance {
    const val MAX_BYTES = 30 * 16000 * 2 // matches the 30 s native STT input bound
    private val slot = AtomicReference<ByteArray?>(null)

    fun offer(pcm: ByteArray, debug: Boolean = BuildConfig.DEBUG): Boolean {
        if (!debug || pcm.isEmpty() || pcm.size > MAX_BYTES || pcm.size % 2 != 0) { pcm.fill(0); return false }
        slot.getAndSet(pcm)?.fill(0)
        return true
    }

    fun take(debug: Boolean = BuildConfig.DEBUG): ByteArray? = if (!debug) null else slot.getAndSet(null)

    /** 16 kHz mono 16-bit PCM WAV only; returns the sample bytes or null. */
    fun parseWav(bytes: ByteArray): ByteArray? {
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        fun tag(i: Int) = String(bytes, i, 4, Charsets.US_ASCII)
        if (bytes.size < 44 || tag(0) != "RIFF" || tag(8) != "WAVE") return null
        var i = 12
        var fmtOk = false
        while (i + 8 <= bytes.size) {
            val size = u32(i + 4)
            if (size < 0 || size > bytes.size - i - 8L) return null
            val body = i + 8
            when (tag(i)) {
                "fmt " -> { if (size < 16) return null
                    fmtOk = u16(body) == 1 && u16(body + 2) == 1 && u32(body + 4) == 16000L && u16(body + 14) == 16 }
                "data" -> return if (fmtOk && size > 0 && size % 2 == 0L) bytes.copyOfRange(body, body + size.toInt()) else null
            }
            i = body + size.toInt() + (size.toInt() and 1)
        }
        return null
    }
}
