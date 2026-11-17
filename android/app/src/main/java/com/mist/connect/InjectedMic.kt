package com.mist.connect

/** Debug test harness: a recorded clip that replaces what the microphone returns, sample for
 * sample, so wake word, stop word and endpointing run on it exactly as on live sound. Followed
 * by a short stretch of digital silence, then the real microphone again. Inert in release. */
internal object InjectedMic {
    const val TAIL_SAMPLES = 16000 * 3 / 2
    private var feed: ShortArray? = null
    private var pos = 0

    @Synchronized fun offer(pcm: ByteArray, debug: Boolean = BuildConfig.DEBUG): Boolean {
        if (!debug || pcm.isEmpty() || pcm.size > InjectedUtterance.MAX_BYTES || pcm.size % 2 != 0) { pcm.fill(0); return false }
        val samples = ShortArray(pcm.size / 2 + TAIL_SAMPLES)
        for (i in 0 until pcm.size / 2) samples[i] = ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
        pcm.fill(0)
        feed?.fill(0)
        feed = samples; pos = 0
        return true
    }

    /** Overwrites block[offset until offset+n] with queued samples while any remain. */
    @Synchronized fun overlay(block: ShortArray, offset: Int, n: Int, debug: Boolean = BuildConfig.DEBUG) {
        if (!debug || n <= 0) return
        val f = feed ?: return
        val count = minOf(n, f.size - pos)
        System.arraycopy(f, pos, block, offset, count)
        pos += count
        if (pos >= f.size) { f.fill(0); feed = null; pos = 0 }
    }

    @Synchronized fun pending(): Boolean = feed != null
}
