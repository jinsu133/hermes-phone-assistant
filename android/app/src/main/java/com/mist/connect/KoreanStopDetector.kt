package com.mist.connect

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/** Local full-vocabulary decoding: restricted Korean grammar produced false wakes.
 * Optional diagnostic preview is bounded, memory-only and foreground-only.
 */
internal class KoreanStopDetector(context: Context, active: () -> Boolean, private val preview: (String, Boolean) -> Unit) : AutoCloseable {
    private val gate = WakePhraseGate()
    private val stopGate = StopPhraseGate()
    var stopped = false
        private set
    var endpointEvidence: SpeechEndGate.Evidence = SpeechEndGate.Evidence.NONE
        private set
    private val model: Model
    private val recognizer: Recognizer
    var wakeEndSample: Long = 0
        private set
    private var samples = 0L
    init {
        val root = File(context.filesDir, "wake-model-v1")
        val complete = File(root, "complete")
        if (!complete.isFile) {
            root.mkdirs()
            var bytes = 0L
            ZipInputStream(context.assets.open("vosk-model-small-ko-0.22.zip")).use { zip ->
                while (true) {
                    check(active()) { "Probe cancelled" }
                    val entry = zip.nextEntry ?: break
                    val out = File(root, entry.name).canonicalFile
                    require(out.path.startsWith(root.canonicalPath + File.separator))
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile!!.mkdirs()
                        out.outputStream().use { stream ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                check(active()) { "Probe cancelled" }
                                val n = zip.read(buffer)
                                if (n < 0) break
                                bytes += n
                                require(bytes <= 300L * 1024 * 1024)
                                stream.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(File(root, "vosk-model-small-ko-0.22/am/final.mdl").isFile)
            complete.writeText("vosk-model-small-ko-0.22")
        }
        check(active()) { "Probe cancelled" }
        model = Model(File(root, "vosk-model-small-ko-0.22").path)
        try { recognizer = Recognizer(model, 16000f).apply { setWords(true); setPartialWords(true) } }
        catch (e: Exception) { model.close(); throw e }

    }
    fun acceptStop(pcm: ShortArray, count: Int): Boolean {
        require(count in 0..pcm.size)
        endpointEvidence = SpeechEndGate.Evidence.NONE
        if (count == 0) return stopped
        // Vosk true means decoder endpoint, NOT "speech present". Read the matching
        // result once; keep exact completed STOP handling independent of endpointing.
        val final = recognizer.acceptWaveForm(pcm,count)
        val result = JSONObject(if(final) recognizer.result else recognizer.partialResult)
        val text = result.optString(if(final) "text" else "partial")
        if (stopGate.accept(text,final)) stopped=true
        val words = result.optJSONArray(if (final) "result" else "partial_result")
        var wordEnd = -1L
        for (i in 0 until minOf(words?.length() ?: 0, 1024)) {
            val word = words?.optJSONObject(i) ?: continue
            val token = word.optString("word")
            val end = word.optDouble("end", Double.NaN)
            if (token != "[unk]" && token.any { it.isLetterOrDigit() } &&
                end.isFinite() && end >= 0.0 && end < Long.MAX_VALUE / 16000.0) {
                wordEnd = maxOf(wordEnd, (end * 16000).toLong())
            }
        }
        endpointEvidence = SpeechEndGate.Evidence(text, final, wordEnd)
        return stopped
    }
    fun accept(pcm: ShortArray, count: Int): Boolean {
        val final = recognizer.acceptWaveForm(pcm, count)
        val result = JSONObject(if (final) recognizer.result else recognizer.partialResult)
        val text = result.optString(if (final) "text" else "partial")
        samples += count
        preview(text, final)
        if (stopGate.accept(text, final)) stopped = true
        val hit = gate.accept(text, final)
        if (hit) {
            val words = result.optJSONArray(if (final) "result" else "partial_result")
            val first = words?.optJSONObject(0)
            val word = first?.optString("word") ?: ""
            wakeEndSample = if (word == "헤르메스" || word.equals("hermes", true))
                kotlin.math.ceil(first!!.optDouble("end", samples / 16000.0) * 16000).toLong()
                else samples // Never upload ambient pre-wake audio when timestamps are unavailable.
        }
        return hit
    }
    fun reset() { recognizer.reset(); gate.reset(); stopGate.reset(); stopped = false; samples = 0L; wakeEndSample = 0L; endpointEvidence = SpeechEndGate.Evidence.NONE }
    override fun close() { try { recognizer.close() } finally { model.close(); gate.reset() } }
}
