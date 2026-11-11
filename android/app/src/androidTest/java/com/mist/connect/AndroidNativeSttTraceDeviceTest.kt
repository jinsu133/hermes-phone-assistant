package com.mist.connect

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly select this class only. At most six ASR calls, one attempt each.
 * Uses only parent-provided synthetic-assets-v2/stt-diag/{a,b,c}.pcm packaged in
 * androidTest assets. No AudioRecord, speaker, model download, HTTP, service or prefs.
 * G1 measures the existing recognizer; quality failure is NOT an expected pass.
 */
class AndroidNativeSttTraceDeviceTest {
    private enum class Case(val order: IntArray, val gapBytes: Int) {
        A(intArrayOf(0), 0), B(intArrayOf(1), 0), C(intArrayOf(2), 0),
        ABC_GAP_500_MS(intArrayOf(0, 1, 2), 16000),
        CBA_GAP_500_MS(intArrayOf(2, 1, 0), 16000),
        ABC_NO_GAP(intArrayOf(0, 1, 2), 0)
    }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    private fun readAsset(index: Int): ByteArray {
        val path = arrayOf("stt-diag/a.pcm", "stt-diag/b.pcm", "stt-diag/c.pcm")[index]
        val scratch = ByteArray(AndroidNativeStt.MAX_PCM + 1)
        try {
            var length = 0
            InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
                while (length < scratch.size) {
                    val n = input.read(scratch, length, scratch.size - length)
                    if (n < 0) break
                    if (n == 0) throw IllegalStateException("ASSET_READ_STALLED")
                    length += n
                }
            }
            check(length in 320..AndroidNativeStt.MAX_PCM && length % 2 == 0) { "ASSET_PCM_INVALID" }
            return scratch.copyOf(length)
        } finally { scratch.fill(0) }
    }

    private fun combine(sources: Array<ByteArray?>, case: Case): ByteArray {
        val size = case.order.sumOf { sources[it]!!.size } + case.gapBytes * (case.order.size - 1)
        check(size in 320..AndroidNativeStt.MAX_PCM) { "CASE_PCM_OUT_OF_RANGE" }
        val pcm = ByteArray(size)
        var at = 0
        for ((i, index) in case.order.withIndex()) {
            val source = sources[index]!!
            source.copyInto(pcm, at)
            at += source.size
            if (i != case.order.lastIndex) at += case.gapBytes // PCM silence, NOT wall-clock sleep
        }
        return pcm
    }

    // Missing/malformed fields are NOT proof that native references were released.
    private fun cleanupConfirmed(trace: JSONObject?): Boolean = try {
        trace != null && trace.getBoolean("cleanup_required") && trace.getBoolean("cleanup_seen") &&
            trace.getBoolean("cleanup_confirmed") && trace.getBoolean("destroy_wait_confirmed") &&
            trace.getBoolean("destroy_began") && trace.getBoolean("destroy_ended") &&
            trace.getBoolean("destroy_succeeded") && !trace.getBoolean("writer_alive_at_cleanup") &&
            (!trace.getBoolean("writer_started") || trace.getBoolean("writer_ended"))
    } catch (_: Throwable) { false }

    private fun tracePass(trace: JSONObject?, pcmSize: Int, ids: MutableSet<Long>): Boolean {
        if (trace == null) return false
        return try {
            val events = trace.getJSONArray("events")
            val terminal = trace.getJSONObject("first_terminal")
            var previousSeq = 0L
            var previousMs = 0L
            val kinds = HashSet<String>()
            var ordered = true
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val seq = event.getLong("seq")
                val ms = event.getLong("t_ms")
                ordered = ordered && seq > previousSeq && ms >= previousMs
                previousSeq = seq; previousMs = ms
                kinds.add(event.getString("kind"))
            }
            val terminalBytes = terminal.getLong("bytes")
            val writerBytes = trace.getLong("writer_bytes")
            trace.getInt("schema") == 2 && trace.getBoolean("sealed") &&
                ids.add(trace.getLong("request_id")) &&
                trace.toString().toByteArray(Charsets.UTF_8).size <= AndroidSttTrace.MAX_JSON_BYTES &&
                events.length() in 1..AndroidSttTrace.MAX_EVENTS && ordered &&
                trace.getInt("input_bytes") == pcmSize &&
                terminal.getString("kind") != "NONE" && terminal.getBoolean("atomic_claim") &&
                terminal.getLong("claim_before_ms") in 0..terminal.getLong("claim_after_ms") &&
                terminal.getLong("claim_after_ms") == terminal.getLong("t_ms") &&
                terminal.getInt("write_in_flight_bytes") in 0..4096 &&
                (!terminal.getBoolean("normal_eof") || terminalBytes == pcmSize.toLong()) &&
                terminalBytes in 0..writerBytes && writerBytes <= pcmSize.toLong() &&
                trace.getLong("callback_count") > 0 &&
                kinds.any { it.startsWith("CB_") } &&
                kinds.contains("SUPPORT_BEGIN") && kinds.contains("SUPPORT_END") &&
                kinds.contains("START_LISTENING") &&
                trace.getBoolean("cleanup_required") && trace.getBoolean("cleanup_seen") &&
                trace.getBoolean("cleanup_confirmed") && trace.getBoolean("destroy_wait_confirmed") &&
                trace.getBoolean("destroy_began") && trace.getBoolean("destroy_ended") &&
                trace.getBoolean("destroy_succeeded") &&
                !trace.getBoolean("writer_alive_at_cleanup") &&
                (!trace.getBoolean("writer_started") || trace.getBoolean("writer_ended")) &&
                (!trace.getBoolean("normal_eof") || writerBytes == pcmSize.toLong())
        } catch (_: Throwable) { false }
    }

    @Test fun sixFixedSyntheticCasesOnce() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val active = AtomicBoolean(true)
        val sources = arrayOfNulls<ByteArray>(3)
        // Strings exist only in RAM, never in status/error/assertion messages or files.
        val standalone = arrayOfNulls<String>(3)
        val ids = HashSet<Long>()
        var diagnosticsPass = true
        var qualityPass = true
        val run = AndroidSttDiagnosticRun(active)
        try {
            try { for (i in sources.indices) sources[i] = readAsset(i) }
            catch (_: Throwable) { throw AssertionError("SYNTHETIC_ASSETS_V2_UNAVAILABLE_OR_INVALID") }
            run.runCases(Case.values().size) { index ->
                val case = Case.values()[index]
                var pcm: ByteArray? = null
                var mayWipePcm = true // no ASR call yet; each combine makes a separate copy
                var response: JSONObject? = null
                val summary = JSONObject().put("case", case.name).put("attempt", 1)
                    .put("active_before", active.get())
                try {
                    val casePcm = combine(sources, case)
                    pcm = casePcm
                    summary.put("pcm_bytes", casePcm.size)
                    mayWipePcm = false
                    run.callStarting()
                    // Real AndroidNativeStt, not an HTTP endpoint or recognizer double.
                    val request = AndroidSttRequest(casePcm.size)
                    if (!active.get()) request.cancel()
                    response = try { AndroidNativeStt.transcribeDiagnosed(inst.targetContext, casePcm, request) }
                    finally { request.cancel() }
                    val diagnostic = response.optJSONObject("stt_diagnostics")
                    val cleanupError = response.optString("error", "") == "stt_cleanup_unconfirmed"
                    mayWipePcm = !cleanupError && response.opt("stt_cleanup_confirmed") == true && cleanupConfirmed(diagnostic)
                    val diagnosticOk = tracePass(diagnostic, casePcm.size, ids)
                    run.observe(mayWipePcm, diagnosticOk, cleanupError)
                    diagnosticsPass = diagnosticsPass && diagnosticOk && mayWipePcm
                    if (run.reason() == AndroidSttDiagnosticRun.Stop.NONE) {
                        val recognized = response.optString("transcript", "")
                        response.remove("transcript")
                        val normalizedText = normalized(recognized)
                        val success = response.optBoolean("success", false)
                        val single = case.order.size == 1
                        if (single) standalone[case.order[0]] = normalizedText
                        val expected = case.order.joinToString("") { standalone[it].orEmpty() }
                        val referenceReady = case.order.all { !standalone[it].isNullOrEmpty() }
                        val match = referenceReady && normalizedText == expected
                        val contains = JSONArray()
                        for (i in standalone.indices) {
                            val reference = standalone[i]
                            contains.put(JSONObject().put("standalone_index", i)
                                .put("reference_ready", !reference.isNullOrEmpty())
                                .put("required", i in case.order)
                                .put("whole_contained", !reference.isNullOrEmpty() && normalizedText.contains(reference)))
                        }
                        val allRequiredContained = case.order.all { i ->
                            !standalone[i].isNullOrEmpty() && normalizedText.contains(standalone[i]!!)
                        }
                        val qualityOk = success && response.optBoolean("on_device", false) &&
                            normalizedText.isNotEmpty() && match && allRequiredContained
                        qualityPass = qualityPass && qualityOk
                        summary.put("transcript_length", recognized.length)
                            .put("normalized_length", normalizedText.length)
                            .put("comparison", if (single) "SELF_BASELINE" else "ORDERED_STANDALONE_BASELINES")
                            .put("reference_ready", referenceReady).put("normalized_match", match)
                            .put("whole_standalone_containment", contains)
                            .put("diagnostics_pass", diagnosticOk).put("quality_pass", qualityOk)
                            .put("active_after", active.get())
                        if (diagnostic != null) summary.put("stt_diagnostics", diagnostic)
                    } else {
                        // No unvalidated diagnostic JSON, exception or response is emitted.
                        qualityPass = false
                        summary.put("diagnostics_pass", false).put("quality_pass", false)
                    }
                } catch (_: Throwable) {
                    // Deliberately omit exception text, class, stack, transcript and whole response.
                    run.stop(AndroidSttDiagnosticRun.Stop.CALL_OR_PROCESSING_FAILED)
                    diagnosticsPass = false; qualityPass = false
                    summary.put("diagnostics_pass", false).put("quality_pass", false)
                } finally {
                    try { response?.remove("transcript") }
                    catch (_: Throwable) {
                        run.stop(AndroidSttDiagnosticRun.Stop.CALL_OR_PROCESSING_FAILED)
                        diagnosticsPass = false; qualityPass = false
                    }
                    response = null
                    // A possibly live writer may still reference this exact array. Do not mutate it.
                    // Drop our reference; native worker/GC governs its remaining lifetime.
                    if (mayWipePcm) pcm?.fill(0)
                    pcm = null
                }
                summary.put("stop_reason", run.reason().name).put("active_after", active.get())
                    .put("pcm_wipe_safe", mayWipePcm).put("asr_calls", run.calls())
                    .put("attempted_cases", run.attempted())
                inst.sendStatus(0, Bundle().apply { putString("android_stt_trace_case", summary.toString()) })
            }
            inst.sendStatus(0, Bundle().apply {
                putString("android_stt_trace_summary", JSONObject().put("case_count", run.completed())
                    .put("planned_cases", Case.values().size).put("attempted_cases", run.attempted())
                    .put("asr_calls", run.calls()).put("stop_reason", run.reason().name)
                    .put("aborted", run.reason() != AndroidSttDiagnosticRun.Stop.NONE)
                    .put("all_cases_completed", run.completed() == 6 && run.reason() == AndroidSttDiagnosticRun.Stop.NONE)
                    .put("active_after", active.get())
                    .put("diagnostics_pass", diagnosticsPass && run.reason() == AndroidSttDiagnosticRun.Stop.NONE)
                    .put("quality_pass", qualityPass && run.reason() == AndroidSttDiagnosticRun.Stop.NONE).toString())
            })
            // Both outcomes have already been emitted, even when quality fails historically.
            assertTrue("DIAGNOSTIC_TRACE_ACCEPTANCE_FAILED", run.completed() == 6 && run.reason() == AndroidSttDiagnosticRun.Stop.NONE && diagnosticsPass)
            assertTrue("ASR_QUALITY_ACCEPTANCE_FAILED", qualityPass)
        } finally {
            active.set(false)
            for (i in sources.indices) { sources[i]?.fill(0); sources[i] = null; standalone[i] = null }
            ids.clear()
            // JVM/Android immutable Strings cannot be securely overwritten; release references only.
        }
    }
}
