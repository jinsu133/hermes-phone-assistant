package com.mist.connect

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit opt-in only. Ten calls: paired FAST/REALTIME_20MS for A/B/C/ABC/CBA.
 * Uses only parent-provided synthetic-assets-v2/stt-diag/{a,b,c}.pcm packaged in
 * androidTest assets. No AudioRecord, speaker, model download, HTTP, service or prefs.
 * G1 measures the existing recognizer; quality failure is NOT an expected pass.
 */
class AndroidNativeSttPacedDeviceTest {
    private enum class Case(val order: IntArray, val gapBytes: Int) {
        A(intArrayOf(0), 0), B(intArrayOf(1), 0), C(intArrayOf(2), 0),
        ABC_GAP_500_MS(intArrayOf(0, 1, 2), 16000),
        CBA_GAP_500_MS(intArrayOf(2, 1, 0), 16000)
    }

    private val modes = arrayOf(AndroidSttPcmPacer.Mode.FAST, AndroidSttPcmPacer.Mode.REALTIME_20MS)
    private val assetHashes = arrayOf("4c179ad9fb7ab708834d2733210fe79caf26266549c9b64d3cf038f9b4b6b8e8","5ade40fd824fe4667e89f1443f2817fa0e43337e553cfe1e6fb2dff206fe6ece","6eff62defe29b0de028157d1c7fc3da93e7eb008ea0d491669dbf2911d1c5732")
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
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
            val result = scratch.copyOf(length)
            check(sha256(result) == assetHashes[index]) { "SYNTHETIC_ASSET_IDENTITY_CHANGED" }
            return result
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

    // Exact response scalars and actual completion snapshot must agree before export.
    private fun checkedDiagnostic(response: JSONObject, pcmSize: Int, ids: MutableSet<Long>): JSONObject? = try {
        val success = AndroidSttMetadataGate.boolean(response, "success")
        val cleanup = AndroidSttMetadataGate.boolean(response, "stt_cleanup_confirmed")
        AndroidSttMetadataGate.boolean(response, "on_device")
        check(AndroidSttMetadataGate.number(response, "stt_contract") == 2L)
        val text = response.get("transcript")
        check(text is String && text.length <= 6000)
        val reason = response.get("stt_reason")
        check(reason is String && reason in setOf("recognized", "no_match", "speech_timeout", "empty_final", "incomplete", "cancelled", "failed"))
        check(success == (reason == "recognized"))
        val safe = AndroidSttMetadataGate.checked(response.optJSONObject("stt_diagnostics"), pcmSize, ids)
        check(safe != null)
        val snapshot = safe.getJSONObject("completion_snapshot")
        check(AndroidSttMetadataGate.boolean(snapshot, "success") == success &&
            AndroidSttMetadataGate.number(snapshot, "text_chars") == text.length.toLong() &&
            AndroidSttMetadataGate.boolean(snapshot, "cleanup_confirmed") == cleanup)
        safe
    } catch (_: Throwable) { null }

    @Test fun fastVsPacedMatrixOnce() {
        check(InstrumentationRegistry.getArguments().getString("r04_paced_pcm") == "synthetic-only-v1") {
            "EXPLICIT_R04_SYNTHETIC_OPT_IN_REQUIRED"
        }
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
            run.runCases(Case.values().size * modes.size) { index ->
                val case = Case.values()[index / modes.size]
                val mode = modes[index % modes.size]
                var pcm: ByteArray? = null
                var mayWipePcm = true // no ASR call yet; each combine makes a separate copy
                var response: JSONObject? = null
                val summary = JSONObject().put("case", case.name).put("attempt", 1)
                    .put("matrix_index", index).put("mode", mode.name)
                    .put("deadline_ms", 30000).put("chunk_bytes", 640)
                    .put("active_before", active.get())
                try {
                    val casePcm = combine(sources, case)
                    pcm = casePcm
                    summary.put("pcm_bytes", casePcm.size).put("pcm_sha256", sha256(casePcm))
                    mayWipePcm = false
                    run.callStarting()
                    // Real AndroidNativeStt, not an HTTP endpoint or recognizer double.
                    val request = AndroidSttRequest(casePcm.size)
                    if (!active.get()) request.cancel()
                    response = try { AndroidNativeStt.transcribeDiagnosedPaced(inst.targetContext, casePcm, request, mode) }
                    finally { request.cancel() }
                    val rawDiagnostic = response.optJSONObject("stt_diagnostics")
                    val cleanupError = response.opt("error") == "stt_cleanup_unconfirmed"
                    mayWipePcm = !cleanupError && response.opt("stt_cleanup_confirmed") == true &&
                        AndroidSttMetadataGate.cleanupConfirmed(rawDiagnostic)
                    val diagnostic = checkedDiagnostic(response, casePcm.size, ids)
                    val diagnosticOk = diagnostic != null
                    val supplyOk = diagnostic != null && diagnostic.getLong("writer_bytes") == casePcm.size.toLong() &&
                        diagnostic.getBoolean("writer_started") && diagnostic.getBoolean("writer_ended") &&
                        diagnostic.getBoolean("normal_eof") && !diagnostic.getBoolean("writer_failed")
                    val pacingOk = supplyOk && (mode == AndroidSttPcmPacer.Mode.FAST ||
                        (diagnostic!!.getLong("writer_eof_ms") - diagnostic.getLong("writer_start_ms")) >= casePcm.size / 32L)
                    // No original object (including unknown string fields) crosses this boundary.
                    if (diagnosticOk) {
                        summary.put("supply_pass", supplyOk).put("pacing_assessable", supplyOk).put("pacing_pass", pacingOk)
                            .put("success", response.getBoolean("success"))
                            .put("on_device", response.getBoolean("on_device"))
                            .put("stt_cleanup_confirmed", response.getBoolean("stt_cleanup_confirmed"))
                            .put("stt_contract", response.getInt("stt_contract"))
                            .put("stt_reason", response.getString("stt_reason"))
                            .put("stt_diagnostics", diagnostic)
                    }
                    run.observe(mayWipePcm, diagnosticOk, cleanupError)
                    diagnosticsPass = diagnosticsPass && diagnosticOk && mayWipePcm && (!supplyOk || pacingOk)
                    if (run.reason() == AndroidSttDiagnosticRun.Stop.NONE) {
                        val recognized = response.optString("transcript", "")
                        response.remove("transcript")
                        val normalizedText = normalized(recognized)
                        val success = response.optBoolean("success", false)
                        val single = case.order.size == 1
                        if (single && mode == AndroidSttPcmPacer.Mode.FAST) standalone[case.order[0]] = normalizedText
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
                        val qualityOk = success && supplyOk && pacingOk && response.optBoolean("on_device", false) &&
                            normalizedText.isNotEmpty() && match && allRequiredContained
                        qualityPass = qualityPass && qualityOk
                        summary.put("transcript_length", recognized.length)
                            .put("normalized_length", normalizedText.length)
                            .put("comparison", if (single && mode == AndroidSttPcmPacer.Mode.FAST) "FAST_SELF_BASELINE" else "FIXED_FAST_STANDALONE_BASELINES")
                            .put("reference_ready", referenceReady).put("normalized_match", match)
                            .put("whole_standalone_containment", contains)
                            .put("diagnostics_pass", diagnosticOk).put("quality_pass", qualityOk)
                            .put("active_after", active.get())
                    } else {
                        // No unvalidated diagnostic JSON, exception or response is emitted.
                        qualityPass = false
                        summary.put("diagnostics_pass", false).put("quality_pass", false)
                    }
                } catch (_: Throwable) {
                    // Deliberately omit exception text, class, stack, transcript and whole response.
                    if (!mayWipePcm) run.observe(false, false, false)
                    else run.stop(AndroidSttDiagnosticRun.Stop.CALL_OR_PROCESSING_FAILED)
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
                // A distinct exact aborted schema: never publish partially validated fields.
                val exported = if (run.reason() == AndroidSttDiagnosticRun.Stop.NONE) summary else JSONObject()
                    .put("case", case.name).put("attempt", 1).put("matrix_index", index).put("mode", mode.name)
                    .put("deadline_ms", 30000).put("chunk_bytes", 640).put("active_before", summary.getBoolean("active_before"))
                    .put("active_after", false).put("diagnostics_pass", false).put("quality_pass", false)
                    .put("stop_reason", run.reason().name).put("pcm_wipe_safe", mayWipePcm)
                    .put("asr_calls", run.calls()).put("attempted_cases", run.attempted())
                val encoded = exported.toString()
                check(encoded.toByteArray(Charsets.UTF_8).size <= 32768) { "R04_CASE_MANIFEST_BOUND" }
                inst.sendStatus(0, Bundle().apply { putString("r04_stt_case", encoded) })
            }
            inst.sendStatus(0, Bundle().apply {
                putString("r04_stt_summary", JSONObject().put("case_count", run.completed())
                    .put("planned_cases", Case.values().size * modes.size).put("attempted_cases", run.attempted())
                    .put("asr_calls", run.calls()).put("stop_reason", run.reason().name)
                    .put("aborted", run.reason() != AndroidSttDiagnosticRun.Stop.NONE)
                    .put("all_cases_completed", run.completed() == 10 && run.reason() == AndroidSttDiagnosticRun.Stop.NONE)
                    .put("active_after", active.get())
                    .put("diagnostics_pass", diagnosticsPass && run.reason() == AndroidSttDiagnosticRun.Stop.NONE)
                    .put("quality_pass", qualityPass && run.reason() == AndroidSttDiagnosticRun.Stop.NONE).toString())
            })
            // Both outcomes have already been emitted, even when quality fails historically.
            assertTrue("DIAGNOSTIC_TRACE_ACCEPTANCE_FAILED", run.completed() == 10 && run.reason() == AndroidSttDiagnosticRun.Stop.NONE && diagnosticsPass)
            assertTrue("ASR_QUALITY_ACCEPTANCE_FAILED", qualityPass)
        } finally {
            active.set(false)
            for (i in sources.indices) { sources[i]?.fill(0); sources[i] = null; standalone[i] = null }
            ids.clear()
            // JVM/Android immutable Strings cannot be securely overwritten; release references only.
        }
    }
}
