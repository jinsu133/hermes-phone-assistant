package com.mist.connect

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Prepared metadata-only Android/JUnit tests: real trace producer and real run gate,
 * but NO recognizer, PCM source, provider, native, microphone or speaker execution.
 * Parent compiles/runs sequentially after independent review.
 */
class AndroidSttMetadataGateTest {
    private fun good(): JSONObject {
        val t = AndroidSttTrace(640)
        t.admitted()
        t.event(AndroidSttTrace.Kind.SUPPORT_BEGIN, 0, 0, false)
        t.event(AndroidSttTrace.Kind.SUPPORT_END, 0, 0, true)
        t.event(AndroidSttTrace.Kind.START_LISTENING, 0, 0, false)
        t.event(AndroidSttTrace.Kind.START_RETURNED, 0, 0, true)
        t.writerStarted(); t.supplied(640); t.writerEof(); t.writerEnd(false)
        t.event(AndroidSttTrace.Kind.CB_RESULTS, 0, 7, false)
        assertTrue(AndroidSttTrace.claim(AtomicBoolean(false), t, AndroidSttTrace.Terminal.RESULT, 0))
        t.event(AndroidSttTrace.Kind.DESTROY_BEGIN, 0, 0, true)
        t.event(AndroidSttTrace.Kind.DESTROY_END, 0, 0, true)
        t.event(AndroidSttTrace.Kind.PIPE_CLOSE, 0, 0, true)
        t.event(AndroidSttTrace.Kind.PIPE_CLOSE, 0, 0, true)
        t.cleanup(true, false, true); t.completionSnapshot(true, 7, true)
        return JSONObject(t.sealJson())
    }
    private fun refused(t: JSONObject) {
        assertNull(AndroidSttMetadataGate.checked(t, 640, HashSet()))
    }
    @Test fun validCompleteTraceExportsOnlyValidatedCopy() {
        val t = good(); val ids = HashSet<Long>()
        val safe = AndroidSttMetadataGate.checked(t, 640, ids)
        assertNotNull(safe); assertNotSame(t, safe)
        t.put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL")
        assertFalse(safe!!.has("PRIVATE_SENTINEL"))
    }
    @Test fun unknownStringAtEveryNestingLevelIsRejectedBeforeExport() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL") },
            { it.getJSONObject("first_terminal").put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL") },
            { it.getJSONObject("completion_snapshot").put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL") },
            { it.getJSONObject("native_callbacks").put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL") },
            { it.getJSONObject("native_callbacks").getJSONObject("CB_RESULTS").put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL") },
            { it.getJSONArray("events").getJSONObject(0).put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL") })
        for (mutate in mutations) { val t = good(); mutate(t); refused(t) }
    }
    @Test fun everyTopLevelAndSnapshotFieldIsRequired() {
        val template = good(); val keys = ArrayList<String>(); val it = template.keys()
        while (it.hasNext()) keys.add(it.next())
        for (key in keys) { val t = good(); t.remove(key); refused(t) }
        for (key in listOf("seen", "success", "text_chars", "cleanup_confirmed")) {
            val t = good(); t.getJSONObject("completion_snapshot").remove(key); refused(t)
        }
    }
    @Test fun missingCharsAndCoercedCountsAreRejected() {
        val missing = good(); missing.getJSONObject("native_callbacks").getJSONObject("CB_RESULTS").remove("chars"); refused(missing)
        for (value in listOf<Any>("1", true, -1, 1.0)) {
            val t = good(); t.getJSONObject("native_callbacks").getJSONObject("CB_RESULTS").put("count", value); refused(t)
        }
        val wrongFlag = good(); wrongFlag.getJSONObject("completion_snapshot").put("seen", "true"); refused(wrongFlag)
    }
    @Test fun callbackAndCharacterTotalsCannotContradictEvents() {
        for (key in listOf("count", "chars")) {
            val t = good(); t.getJSONObject("native_callbacks").getJSONObject("CB_RESULTS").put(key, 99); refused(t)
        }
        val t = good(); t.put("segment_chars", 1); refused(t)
    }
    @Test fun completionSupplyTerminalAndCleanupContradictionsFailClosed() {
        for (key in listOf("cleanup_confirmed", "destroy_succeeded", "writer_ended", "sealed")) {
            val t = good(); t.put(key, false); refused(t)
        }
        val missingTerminal = good(); missingTerminal.getJSONObject("first_terminal").put("kind", "NONE"); refused(missingTerminal)
        val missingEof = good(); missingEof.put("normal_eof", false); refused(missingEof)
        val snapshot = good(); snapshot.getJSONObject("completion_snapshot").put("success", false); refused(snapshot)
        val events = good(); events.getJSONArray("events").getJSONObject(0).put("seq", 2); refused(events)
    }
    @Test fun duplicateRequestIsRejectedWithoutNextObservation() {
        val t = good(); val ids = HashSet<Long>()
        assertNotNull(AndroidSttMetadataGate.checked(t, 640, ids))
        assertNull(AndroidSttMetadataGate.checked(t, 640, ids))
    }
    @Test fun malformedObservationStopsAfterExactlyOneCall() {
        val active = AtomicBoolean(true); val run = AndroidSttDiagnosticRun(active)
        run.runCases(10) {
            run.callStarting()
            val t = good(); t.put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL")
            val safe = AndroidSttMetadataGate.checked(t, 640, HashSet())
            run.observe(AndroidSttMetadataGate.cleanupConfirmed(t), safe != null, false)
        }
        assertEquals(1, run.calls()); assertEquals(1, run.attempted())
        assertEquals(AndroidSttDiagnosticRun.Stop.OBSERVATION_FAILED, run.reason()); assertFalse(active.get())
    }
    @Test fun cleanupUncertaintyTakesPriorityOverMalformedObservation() {
        val active = AtomicBoolean(true); val run = AndroidSttDiagnosticRun(active)
        run.runCases(10) {
            run.callStarting()
            val t = good(); t.getJSONObject("completion_snapshot").remove("cleanup_confirmed")
            t.put("PRIVATE_SENTINEL", "PRIVATE_SENTINEL")
            run.observe(AndroidSttMetadataGate.cleanupConfirmed(t), AndroidSttMetadataGate.checked(t, 640, HashSet()) != null, false)
        }
        assertEquals(1, run.calls()); assertEquals(AndroidSttDiagnosticRun.Stop.CLEANUP_UNCONFIRMED, run.reason())
    }
    @Test fun cleanQualityRedDoesNotChangeFixedTenCallSafetySchedule() {
        val run = AndroidSttDiagnosticRun(AtomicBoolean(true)); val ids = HashSet<Long>()
        var quality = true
        run.runCases(10) { index ->
            run.callStarting(); val t = good()
            run.observe(AndroidSttMetadataGate.cleanupConfirmed(t), AndroidSttMetadataGate.checked(t, 640, ids) != null, false)
            quality = quality && index != 1 // synthetic quality RED, never promoted to pass
        }
        assertEquals(10, run.calls()); assertEquals(10, run.completed())
        assertEquals(AndroidSttDiagnosticRun.Stop.NONE, run.reason()); assertFalse(quality)
    }
}
