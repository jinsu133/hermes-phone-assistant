package com.mist.connect

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import com.mist.connect.AndroidSttTrace.Kind
import com.mist.connect.AndroidSttTrace.Terminal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Offline-only recognition of supplied 16kHz mono PCM. Never owns a microphone. */
internal object AndroidNativeStt {
    const val MAX_PCM = 16000 * 2 * 30
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    // Strong API: the actual cancellation owner must retain and cancel this token.
    // Completion.cancel() and seal() share one monitor; Boolean polling cannot do this.
    fun transcribe(context: Context, pcm: ByteArray, request: AndroidSttRequest): JSONObject =
        transcribeRequest(context, pcm, request, false)

    // Explicit, request-local supplied-PCM experiment. No persisted/default selection.
    fun transcribe(context: Context, pcm: ByteArray, request: AndroidSttRequest,
                   options: AndroidSttInputOptions): JSONObject =
        transcribeRequest(context, pcm, request, false, options.mode(), options).also {
            if (options.mode() != null) it.put("stt_input_mode", AndroidSttInputOptions.WIRE_MODE)
        }

    fun transcribeDiagnosed(context: Context, pcm: ByteArray, request: AndroidSttRequest): JSONObject =
        transcribeRequest(context, pcm, request, true)

    // Instrumentation-only selection; ordinary callers keep the existing unpaced writer.
    fun transcribeDiagnosedPaced(context: Context, pcm: ByteArray, request: AndroidSttRequest,
                                mode: AndroidSttPcmPacer.Mode): JSONObject =
        transcribeRequest(context, pcm, request, true, mode)

    private fun transcribeRequest(context: Context, pcm: ByteArray, request: AndroidSttRequest,
                                  diagnosed: Boolean, pacing: AndroidSttPcmPacer.Mode? = null,
                                  options: AndroidSttInputOptions = AndroidSttInputOptions.LEGACY): JSONObject {
        val completion = request.claim(pcm.size) ?: return JSONObject().put("success", false)
            .put("transcript", "").put("provider", "android-native").put("on_device", true)
            .put("error", "stt_request_invalid").put("stt_contract", 2)
            .put("stt_reason", "failed").put("stt_cleanup_confirmed", true)
        try { return diagnosed(context, pcm, { request.isActive }, completion, diagnosed, pacing, options) }
        finally { request.cancel() } // retire early rejection/exception too; sealed outcomes are immutable
    }

    // Compatibility only: polling gives NO atomic external-cancel/seal guarantee.
    // McpService uses the token overload, never this weak path.
    @Deprecated("Boolean polling cannot linearize cancellation; use AndroidSttRequest")
    fun transcribe(context: Context, pcm: ByteArray, active: () -> Boolean): JSONObject =
        transcribeImpl(context, pcm, active, null)

    @Deprecated("Boolean polling cannot linearize cancellation; use AndroidSttRequest")
    fun transcribeDiagnosed(context: Context, pcm: ByteArray, active: () -> Boolean): JSONObject =
        diagnosed(context, pcm, active, null, true)

    private fun diagnosed(context: Context, pcm: ByteArray, active: () -> Boolean,
                          completion: AndroidSttCompletion?, enabled: Boolean, pacing: AndroidSttPcmPacer.Mode? = null,
                          options: AndroidSttInputOptions = AndroidSttInputOptions.LEGACY): JSONObject {
        var trace: AndroidSttTrace? = null
        if (enabled) AndroidSttTrace.guard { trace = AndroidSttTrace(pcm.size) }
        val result = try { transcribeImpl(context, pcm, active, trace, completion, pacing, options) }
        catch (original: Throwable) {
            observe(trace) { it.sealJson() }
            throw original // diagnostics must never replace the original failure
        }
        // Build the complete diagnostic value before touching the existing response.
        observe(trace) { t ->
            val encoded = t.sealJson()
            if (encoded != null) result.put("stt_diagnostics", JSONObject(encoded))
        }
        return result
    }

    private inline fun observe(trace: AndroidSttTrace?, crossinline action: (AndroidSttTrace) -> Unit) {
        if (trace != null) AndroidSttTrace.guard { action(trace) }
    }

    private fun transcribeImpl(context: Context, pcm: ByteArray, active: () -> Boolean,
                               trace: AndroidSttTrace?, requestCompletion: AndroidSttCompletion? = null,
                               pacing: AndroidSttPcmPacer.Mode? = null,
                               options: AndroidSttInputOptions = AndroidSttInputOptions.LEGACY): JSONObject {
        fun failure(code: String, cleanupConfirmed: Boolean = true) = JSONObject()
            .put("success", false).put("transcript", "")
            .put("provider", "android-native").put("on_device", true).put("error", code)
            .put("stt_contract", 2).put("stt_cleanup_confirmed", cleanupConfirmed)
            .put("stt_reason", when (code) {
                "stt_no_match" -> "no_match"
                "stt_speech_timeout" -> "speech_timeout"
                "stt_empty_final" -> "empty_final"
                "stt_input_incomplete" -> "incomplete"
                "stt_cancelled" -> "cancelled"
                "stt_cleanup_unconfirmed" -> "cleanup_unconfirmed"
                else -> "failed"
            })
        fun rejected(code: String, reason: Terminal): JSONObject {
            observe(trace) { it.terminal(reason, 0) }
            return failure(code)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return rejected("stt_wrong_thread", Terminal.WRONG_THREAD)
        if (pcm.size !in 320..MAX_PCM || pcm.size % 2 != 0) return rejected("stt_pcm_invalid", Terminal.PCM_INVALID)
        if (!active()) return rejected("stt_cancelled", Terminal.CANCELLED)
        // Refuse unschedulable complete inputs before any recognizer or pipe allocation.
        // The default path has no new clock read and retains its original 30s wait.
        val budget = try {
            if (options.mode() == null) null else options.budget(pcm.size, android.os.SystemClock.elapsedRealtime())
        } catch (_: IllegalArgumentException) {
            return rejected("stt_pacing_budget_exceeded", Terminal.PACING_BUDGET)
        }
        if (!busy.compareAndSet(false, true)) return rejected("stt_busy", Terminal.BUSY)
        val completion = requestCompletion ?: AndroidSttCompletion(pcm.size)
        val done = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val destroySucceeded = AtomicBoolean(false)
        var recognizer: SpeechRecognizer? = null // main-thread owned
        val writer = AtomicReference<Thread?>()
        val writerReserved = AtomicBoolean(false)
        val pipes = try { ParcelFileDescriptor.createPipe() }
        catch (_: Exception) { busy.set(false); return rejected("stt_pipe_unavailable", Terminal.PIPE_UNAVAILABLE) }
        observe(trace) { it.admitted() }
        fun finish(text: String?, error: String?, reason: Terminal, code: Int = 0) {
            // Only state + metadata are locked. A terminal is a candidate, not final success.
            if (completion.offer(text, error, reason, code, trace)) {
                if (reason == Terminal.CANCELLED) observe(trace) { it.event(Kind.CANCEL, 0, 0, true) }
                done.countDown()
            }
        }
        fun cancel() {
            completion.cancel()
            finish(null, "stt_cancelled", Terminal.CANCELLED)
        }
        fun accept(text: String, reason: Terminal) {
            if (!active()) { cancel(); return }
            if (text.length > 6000) finish(null, "stt_text_too_large", Terminal.TEXT_LIMIT)
            else if (text.isBlank()) finish(null, "stt_empty_final", reason)
            else finish(text.trim(), null, reason)
        }
        fun success(text: String): JSONObject {
            if (text.length > 6000) return failure("stt_text_too_large")
            return JSONObject().put("success", true).put("transcript", text.trim())
                .put("provider", "android-native").put("on_device", true)
                .put("no_speech", false).put("stt_contract", 2)
                .put("stt_reason", "recognized").put("stt_cleanup_confirmed", true)
        }
        fun text(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        // Recognize in the app's display language so spoken approval phrases (shown in that
        // language) can match, whatever language the host's stt_language option names.
        val preferred = AppText.get(R.string.stt_locale, SttLanguage.FALLBACK)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferred)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipes[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        main.post {
            if (completion.isTerminal() || !active()) { cancel(); return@post }
            try {
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    finish(null, "stt_on_device_unavailable", Terminal.ON_DEVICE_UNAVAILABLE); return@post
                }
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer!!.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { observe(trace) { it.event(Kind.CB_READY, 0, 0, completion.isTerminal()) } }
                    override fun onBeginningOfSpeech() { observe(trace) { it.event(Kind.CB_BEGIN, 0, 0, completion.isTerminal()) } }
                    override fun onRmsChanged(rmsdB: Float) { observe(trace) { it.frameCallback(true) } }
                    override fun onBufferReceived(buffer: ByteArray?) { observe(trace) { it.frameCallback(false) } }
                    override fun onEndOfSpeech() { observe(trace) { it.event(Kind.CB_END, 0, 0, completion.isTerminal()) } }
                    override fun onPartialResults(partialResults: Bundle?) { observe(trace) { it.event(Kind.CB_PARTIAL, 0, text(partialResults).length.toLong(), completion.isTerminal()) } }
                    override fun onEvent(eventType: Int, params: Bundle?) { observe(trace) { it.event(Kind.CB_EVENT, eventType.toLong(), 0, completion.isTerminal()) } }
                    override fun onSegmentResults(segmentResults: Bundle) {
                        observe(trace) { it.event(Kind.CB_SEGMENT, 1, text(segmentResults).length.toLong(), completion.isTerminal()) }
                        val value = text(segmentResults)
                        if (!active()) cancel()
                        else if (!completion.segment(value, value.isNotBlank()))
                            finish(null, "stt_text_too_large", Terminal.TEXT_LIMIT)
                    }
                    override fun onEndOfSegmentedSession() {
                        observe(trace) { it.event(Kind.CB_SEGMENT_END, 0, 0, completion.isTerminal()) }
                        accept(completion.segmentText(), Terminal.SEGMENT_END)
                    }
                    override fun onResults(results: Bundle?) {
                        observe(trace) { it.event(Kind.CB_RESULTS, 0, text(results).length.toLong(), completion.isTerminal()) }
                        accept(text(results), Terminal.RESULT)
                    }
                    override fun onError(error: Int) {
                        observe(trace) { it.event(Kind.CB_ERROR, error.toLong(), 0, completion.isTerminal()) }
                        if (!active()) { cancel(); return }
                        // Native non-recognition is not success or evidence of physical silence.
                        val code = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "stt_no_match"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "stt_speech_timeout"
                            else -> "stt_android_error_$error"
                        }
                        finish(null, code, Terminal.ERROR, error)
                    }
                })
                observe(trace) { it.event(Kind.SUPPORT_BEGIN, 0, 0, false) }
                recognizer!!.checkRecognitionSupport(intent, Executor { command -> main.post(command) },
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            observe(trace) { it.event(Kind.SUPPORT_END, 0, 0, true) }
                            if (completion.isTerminal()) return
                            if (!active()) { cancel(); return }
                            if (!writerReserved.compareAndSet(false, true)) return
                            // No silent fallback to another language: a phrase in the app's language
                            // must never be decoded by a model for a different one.
                            val language = SttLanguage.pick(support.installedOnDeviceLanguages, preferred)
                            if (language == null) {
                                finish(null, if (SttLanguage.primary(preferred) == "ko") "stt_korean_model_missing"
                                    else "stt_language_model_missing", Terminal.MODEL_MISSING); return
                            }
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                            if (budget != null && !budget.canStart(android.os.SystemClock.elapsedRealtime())) {
                                finish(null, "stt_pacing_budget_exceeded", Terminal.PACING_BUDGET); return
                            }
                            try {
                                // Reserve the sole writer before startListening can call back synchronously.
                                val source = Thread({
                                    observe(trace) { it.writerStarted() }
                                    var suppliedAll = false
                                    var writerFailed = false
                                    try {
                                        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
                                            val pacer = pacing?.let { AndroidSttPcmPacer(it) }
                                            var offset = 0
                                            // A terminal stops new writes, but an in-flight final write/close
                                            // may finish during the bounded join. No discard-to-success path.
                                            while (offset < pcm.size && active() && !completion.isTerminal()) {
                                                val count = minOf(pacer?.chunkBytes() ?: 4096, pcm.size - offset)
                                                if (pacer != null && !pacer.beforeWrite(offset, count) {
                                                    active() && !completion.isTerminal()
                                                }) break
                                                // A cancellation/terminal observed after pacing must not start
                                                // another write. Already in-flight IO may finish during cleanup.
                                                if (pacer != null && (!active() || completion.isTerminal())) break
                                                observe(trace) { it.writeBegin(count) }
                                                output.write(pcm, offset, count)
                                                offset += count
                                                completion.supplied(count)
                                                observe(trace) { it.supplied(count) }
                                            }
                                            suppliedAll = offset == pcm.size
                                        }
                                        completion.normalClose()
                                        if (suppliedAll) observe(trace) { it.writerEof() }
                                    } catch (_: Exception) {
                                        writerFailed = true
                                        completion.writerFailed()
                                        finish(null, "stt_input_incomplete", Terminal.SOURCE_IO)
                                    } finally { observe(trace) { it.writerEnd(writerFailed) } }
                                }, "android-stt-pcm-source")
                                writer.set(source)
                                // Explicit source is required. No microphone/default/online fallback.
                                observe(trace) { it.event(Kind.START_LISTENING, 0, 0, false) }
                                recognizer!!.startListening(intent)
                                observe(trace) { it.event(Kind.START_RETURNED, 0, 0, true) }
                                if (!completion.isTerminal() && active()) source.start()
                            } catch (_: Exception) { finish(null, "stt_start_failed", Terminal.START_FAILED) }
                        }
                        override fun onError(error: Int) {
                            observe(trace) { it.event(Kind.SUPPORT_END, error.toLong(), 0, false) }
                            finish(null, "stt_support_error_$error", Terminal.SUPPORT_ERROR, error)
                        }
                    })
            } catch (_: Exception) { finish(null, "stt_setup_failed", Terminal.SETUP_FAILED) }
        }
        var cleanupOk = true
        var interrupted = false
        fun interruptedDuringCleanup() {
            interrupted = true
            cancel() // first failure is preserved by the completion state
            Thread.interrupted()
        }
        // One shared one-second join budget, not one second per retry/phase.
        var joinRemaining = TimeUnit.SECONDS.toNanos(1)
        fun joinWriter() {
            val source = writer.get() ?: return
            while (source.isAlive && joinRemaining > 0) {
                val before = System.nanoTime()
                try { TimeUnit.NANOSECONDS.timedJoin(source, joinRemaining) }
                catch (_: InterruptedException) { interruptedDuringCleanup() }
                finally { joinRemaining -= (System.nanoTime() - before).coerceAtLeast(1) }
            }
        }
        try {
            val deadline = budget?.deadlineMs ?: (android.os.SystemClock.elapsedRealtime() + 30_000)
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                if (!active()) { cancel(); break }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    finish(null, "stt_timeout", Terminal.TIMEOUT); break
                }
            }
        } catch (_: InterruptedException) {
            interruptedDuringCleanup()
        } finally {
            if (!completion.isTerminal()) finish(null, "stt_setup_failed", Terminal.SETUP_FAILED)
            // Let an in-flight final write / normal stream close publish before destructive
            // cleanup. EOF=false at terminal time alone must never reject a valid result.
            joinWriter()
            var destroyPosted = false
            try {
                destroyPosted = main.post {
                    observe(trace) { it.event(Kind.DESTROY_BEGIN, 0, 0, recognizer != null) }
                    try { recognizer?.destroy(); destroySucceeded.set(true) }
                    catch (_: Exception) { /* unknown cleanup remains false */ }
                    finally {
                        observe(trace) { it.event(Kind.DESTROY_END, 0, 0, destroySucceeded.get()) }
                        destroyed.countDown()
                    }
                }
            } catch (_: Exception) { /* still attempt both closes and the remaining join */ }
            val destroyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var destroyWait = false
            while (destroyPosted && !destroyWait) {
                val remaining = destroyDeadline - System.nanoTime()
                if (remaining <= 0) break
                try { destroyWait = destroyed.await(remaining, TimeUnit.NANOSECONDS); break }
                catch (_: InterruptedException) { interruptedDuringCleanup() }
            }
            cleanupOk = destroyWait && destroySucceeded.get()
            for (pipe in pipes) try {
                pipe.close()
                observe(trace) { it.event(Kind.PIPE_CLOSE, 0, 0, true) }
            } catch (_: Exception) {
                cleanupOk = false
                observe(trace) { it.event(Kind.PIPE_CLOSE, 0, 0, false) }
            }
            // The main-thread destroy barrier also fences late setup/writer publication.
            joinWriter()
            cleanupOk = cleanupOk && writer.get()?.isAlive != true
            if (!active()) cancel()
            if (Thread.interrupted()) interruptedDuringCleanup()
            completion.cleanup(cleanupOk)
            // Failed native teardown poisons this owner rather than admitting overlapping work.
            if (cleanupOk) busy.set(false)
            observe(trace) { it.cleanup(cleanupOk, writer.get()?.isAlive == true, destroyWait) }
            if (interrupted) Thread.currentThread().interrupt()
        }
        val outcome = completion.seal()
        observe(trace) { it.completionSnapshot(outcome.error == null, outcome.text.length, outcome.cleanupConfirmed) }
        return if (outcome.error == null) success(outcome.text) else failure(outcome.error, outcome.cleanupConfirmed)
    }
}
