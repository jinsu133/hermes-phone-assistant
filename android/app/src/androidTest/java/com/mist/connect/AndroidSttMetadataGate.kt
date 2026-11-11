package com.mist.connect

import org.json.JSONArray
import org.json.JSONObject

/** Instrumentation output boundary, not a recognizer/owner. No raw payload in failures.
 * Exact schema first; only a validated copy may reach observe/export. Pipe supply is
 * not decoder consumption. Cleanup proof is separate so uncertainty takes priority.
 */
internal object AndroidSttMetadataGate {
    private val bools = names("sealed admitted destroy_began destroy_ended destroy_succeeded writer_started normal_eof writer_ended writer_failed cleanup_required cleanup_seen cleanup_confirmed writer_alive_at_cleanup destroy_wait_confirmed")
    private val numbers = names("schema request_id event_cap json_byte_cap input_bytes elapsed_ms dropped_events callback_count rms_callbacks buffer_callbacks segment_count segment_chars writer_start_ms writer_eof_ms writer_end_ms cleanup_ms destroy_begin_ms destroy_end_ms writer_bytes")
    private val callbacks = names("CB_READY CB_BEGIN CB_END CB_PARTIAL CB_EVENT CB_SEGMENT CB_SEGMENT_END CB_RESULTS CB_ERROR")
    private val textCallbacks = names("CB_PARTIAL CB_SEGMENT CB_RESULTS")
    private val kinds = names("SUPPORT_BEGIN SUPPORT_END START_LISTENING START_RETURNED WRITER_START WRITER_EOF WRITER_END CB_READY CB_BEGIN CB_END CB_PARTIAL CB_EVENT CB_SEGMENT CB_SEGMENT_END CB_RESULTS CB_ERROR CANCEL DESTROY_BEGIN DESTROY_END PIPE_CLOSE CLEANUP")
    private val terminals = names("RESULT SEGMENT_END ERROR CANCELLED TIMEOUT WRONG_THREAD PCM_INVALID BUSY PIPE_UNAVAILABLE ON_DEVICE_UNAVAILABLE MODEL_MISSING TEXT_LIMIT SOURCE_IO START_FAILED SUPPORT_ERROR SETUP_FAILED")
    private fun names(s: String) = s.split(' ').toSet()
    private fun requireMetadata(ok: Boolean) { check(ok) { "INVALID_DIAGNOSTIC_METADATA" } }
    private fun exact(o: JSONObject, keys: Set<String>) {
        val actual = HashSet<String>(); val iterator = o.keys()
        while (iterator.hasNext()) actual.add(iterator.next())
        requireMetadata(actual == keys)
    }
    internal fun boolean(o: JSONObject, key: String): Boolean {
        val v = o.get(key); requireMetadata(v is Boolean); return v as Boolean
    }
    internal fun number(o: JSONObject, key: String, low: Long = 0L, high: Long = Long.MAX_VALUE): Long {
        val v = o.get(key); requireMetadata(v is Int || v is Long)
        val n = (v as Number).toLong(); requireMetadata(n in low..high); return n
    }
    private fun obj(o: JSONObject, key: String): JSONObject {
        val v = o.get(key); requireMetadata(v is JSONObject); return v as JSONObject
    }
    private fun array(o: JSONObject, key: String): JSONArray {
        val v = o.get(key); requireMetadata(v is JSONArray); return v as JSONArray
    }
    private fun enum(o: JSONObject, key: String, allowed: Set<String>): String {
        val v = o.get(key); requireMetadata(v is String && v in allowed); return v as String
    }
    private fun sum(a: Long, b: Long): Long { requireMetadata(a <= Long.MAX_VALUE - b); return a + b }

    internal fun cleanupConfirmed(trace: JSONObject?): Boolean = try {
        requireMetadata(trace != null)
        val t = trace!!
        for (k in names("admitted cleanup_required cleanup_seen cleanup_confirmed destroy_wait_confirmed destroy_began destroy_ended destroy_succeeded")) requireMetadata(boolean(t, k))
        requireMetadata(!boolean(t, "writer_alive_at_cleanup"))
        requireMetadata(!boolean(t, "writer_started") || boolean(t, "writer_ended"))
        val snapshot = obj(t, "completion_snapshot")
        requireMetadata(boolean(snapshot, "seen") && boolean(snapshot, "cleanup_confirmed"))
        true
    } catch (_: Throwable) { false }

    internal fun checked(trace: JSONObject?, pcmSize: Int, ids: MutableSet<Long>): JSONObject? = try {
        requireMetadata(trace != null)
        val t = trace!!
        exact(t, bools + numbers + names("first_terminal events native_callbacks completion_snapshot"))
        for (k in bools) boolean(t, k)
        for (k in numbers) number(t, k)
        requireMetadata(number(t, "schema") == 2L && boolean(t, "sealed"))
        requireMetadata(number(t, "event_cap") == 64L && number(t, "json_byte_cap") == 24576L)
        val id = number(t, "request_id", 1)
        requireMetadata(id !in ids && pcmSize in 320..960000 && pcmSize % 2 == 0)
        requireMetadata(number(t, "input_bytes") == pcmSize.toLong())
        requireMetadata(t.toString().toByteArray(Charsets.UTF_8).size <= 24576)
        requireMetadata(cleanupConfirmed(t))
        val written = number(t, "writer_bytes", 0, pcmSize.toLong())
        requireMetadata(written % 2 == 0L)
        val start = number(t, "writer_start_ms"); val eof = number(t, "writer_eof_ms")
        val end = number(t, "writer_end_ms"); val cleanup = number(t, "cleanup_ms")
        val elapsed = number(t, "elapsed_ms"); val destroyBegin = number(t, "destroy_begin_ms")
        val destroyEnd = number(t, "destroy_end_ms")
        val started = boolean(t, "writer_started"); val ended = boolean(t, "writer_ended")
        val normalEof = boolean(t, "normal_eof"); val failed = boolean(t, "writer_failed")
        requireMetadata(started == ended)
        if (started) requireMetadata(start <= end && end <= cleanup)
        else requireMetadata(!normalEof && !failed && written == 0L && start == 0L && end == 0L && eof == 0L)
        if (normalEof) requireMetadata(started && !failed && written == pcmSize.toLong() && start <= eof && eof <= end)
        else requireMetadata(eof == 0L)
        requireMetadata(destroyBegin <= destroyEnd && destroyEnd <= cleanup && cleanup <= elapsed)
        val terminal = obj(t, "first_terminal")
        exact(terminal, names("kind seq t_ms atomic_claim claim_before_ms claim_after_ms code bytes write_in_flight_bytes normal_eof"))
        val terminalKind = enum(terminal, "kind", terminals)
        val terminalSeq = number(terminal, "seq", 1); val terminalMs = number(terminal, "t_ms")
        requireMetadata(boolean(terminal, "atomic_claim"))
        requireMetadata(number(terminal, "claim_before_ms") <= number(terminal, "claim_after_ms") && number(terminal, "claim_after_ms") == terminalMs && terminalMs <= destroyBegin)
        number(terminal, "code")
        val terminalBytes = number(terminal, "bytes", 0, written)
        val inFlight = number(terminal, "write_in_flight_bytes", 0, 4096)
        requireMetadata(terminalBytes % 2 == 0L)
        if (boolean(terminal, "normal_eof")) requireMetadata(normalEof && terminalBytes == pcmSize.toLong() && inFlight == 0L && eof <= terminalMs)
        else if (normalEof) requireMetadata(terminalMs <= eof)
        val snapshot = obj(t, "completion_snapshot")
        exact(snapshot, names("seen success text_chars cleanup_confirmed"))
        requireMetadata(boolean(snapshot, "seen") && boolean(snapshot, "cleanup_confirmed"))
        val chars = number(snapshot, "text_chars", 0, 6000)
        if (boolean(snapshot, "success")) requireMetadata(chars > 0 && terminalKind in setOf("RESULT", "SEGMENT_END") && normalEof && !failed)
        else requireMetadata(chars == 0L)
        val events = array(t, "events"); requireMetadata(events.length() in 1..64)
        val dropped = number(t, "dropped_events")
        requireMetadata(dropped == 0L || events.length() == 64)
        requireMetadata(terminalSeq <= sum(sum(events.length().toLong(), dropped), 1))
        val observed = callbacks.associateWith { longArrayOf(0, 0) }
        val byKind = HashMap<String, MutableList<JSONObject>>()
        var previousMs = 0L
        for (i in 0 until events.length()) {
            val e = events.get(i); requireMetadata(e is JSONObject); val event = e as JSONObject
            exact(event, names("seq t_ms kind a b flag"))
            val kind = enum(event, "kind", kinds)
            val seq = number(event, "seq"); val ms = number(event, "t_ms")
            number(event, "a", if (kind == "CB_EVENT") Int.MIN_VALUE.toLong() else 0)
            val b = number(event, "b"); boolean(event, "flag")
            requireMetadata(seq == i + 1L + (if (i + 1L >= terminalSeq) 1 else 0))
            requireMetadata(ms in previousMs..elapsed); previousMs = ms
            requireMetadata(if (seq < terminalSeq) ms <= terminalMs else ms >= terminalMs)
            byKind.getOrPut(kind) { ArrayList() }.add(event)
            if (kind in callbacks) {
                observed[kind]!![0]++
                if (kind in textCallbacks) {
                    requireMetadata(b <= Int.MAX_VALUE.toLong())
                    observed[kind]!![1] = sum(observed[kind]!![1], b)
                }
                else requireMetadata(b == 0L)
            }
        }
        val native = obj(t, "native_callbacks"); exact(native, callbacks)
        var countTotal = 0L; var unobserved = 0L
        for (kind in callbacks) {
            val v = obj(native, kind); exact(v, names("count chars"))
            val count = number(v, "count"); val totalChars = number(v, "chars")
            requireMetadata(count >= observed[kind]!![0] && totalChars >= observed[kind]!![1])
            val missingCount = count - observed[kind]!![0]
            val missingCharCap = if (missingCount > Long.MAX_VALUE / Int.MAX_VALUE) Long.MAX_VALUE else missingCount * Int.MAX_VALUE
            requireMetadata(totalChars - observed[kind]!![1] <= missingCharCap)
            requireMetadata(count > 0 || totalChars == 0L)
            if (kind !in textCallbacks) requireMetadata(totalChars == 0L)
            if (dropped == 0L) requireMetadata(count == observed[kind]!![0] && totalChars == observed[kind]!![1])
            countTotal = sum(countTotal, count); unobserved = sum(unobserved, count - observed[kind]!![0])
        }
        requireMetadata(sum(sum(countTotal, number(t, "rms_callbacks")), number(t, "buffer_callbacks")) == number(t, "callback_count"))
        requireMetadata(unobserved <= dropped && number(t, "callback_count") > 0 && callbacks.any { it in byKind })
        requireMetadata(number(t, "segment_count") == number(obj(native, "CB_SEGMENT"), "count") && number(t, "segment_chars") == number(obj(native, "CB_SEGMENT"), "chars"))
        val callbackTerminal = mapOf("RESULT" to "CB_RESULTS", "SEGMENT_END" to "CB_SEGMENT_END", "ERROR" to "CB_ERROR")[terminalKind]
        if (callbackTerminal != null) {
            requireMetadata(number(obj(native, callbackTerminal), "count") > 0)
            if (terminalSeq <= events.length() + 1L) requireMetadata(byKind[callbackTerminal].orEmpty().any {
                number(it, "seq") < terminalSeq && (callbackTerminal != "CB_ERROR" || number(it, "a") == number(terminal, "code"))
            })
        }
        val supportBegin = byKind["SUPPORT_BEGIN"]!!.first()
        val supportEnd = byKind["SUPPORT_END"]!!.first()
        val listening = byKind["START_LISTENING"]!!.first()
        requireMetadata(number(supportBegin, "seq") < number(supportEnd, "seq") && number(supportEnd, "seq") < number(listening, "seq"))
        fun milestone(kind: String, flag: Boolean, ms: Long, a: Long, eventFlag: Boolean?) {
            val seen = byKind[kind].orEmpty()
            requireMetadata(seen.size <= 1 && (seen.isEmpty() || flag))
            if (dropped == 0L) requireMetadata(seen.isNotEmpty() == flag)
            for (e in seen) {
                var previous = 0L
                for (i in 0 until events.length()) {
                    if (events.getJSONObject(i) === e) break
                    previous = number(events.getJSONObject(i), "t_ms")
                }
                // Producer samples now() for the milestone and again when appending.
                requireMetadata(ms in previous..number(e, "t_ms") && number(e, "a") == a && number(e, "b") == 0L && (eventFlag == null || boolean(e, "flag") == eventFlag))
            }
        }
        milestone("WRITER_START", started, start, 0, false)
        milestone("WRITER_EOF", normalEof, eof, pcmSize.toLong(), true)
        milestone("WRITER_END", ended, end, written, failed)
        milestone("DESTROY_BEGIN", boolean(t, "destroy_began"), destroyBegin, 0, null)
        milestone("DESTROY_END", boolean(t, "destroy_ended"), destroyEnd, 0, boolean(t, "destroy_succeeded"))
        milestone("CLEANUP", boolean(t, "cleanup_seen"), cleanup, written, boolean(t, "cleanup_confirmed"))
        if (dropped == 0L) {
            val pipes = byKind["PIPE_CLOSE"].orEmpty()
            requireMetadata(pipes.size == 2 && pipes.all { boolean(it, "flag") && number(it, "t_ms") in destroyEnd..cleanup })
        }
        val safe = JSONObject(t.toString()) // exact validated primitive/enum schema only
        requireMetadata(ids.add(id))
        safe
    } catch (_: Throwable) { null }
}
