package com.mist.connect

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Authenticated, bounded client for the fixed same-device Hermes loopback bridge. */
internal object VoiceBridgeClient {
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val AUDIO_TIMEOUT_MS = 120_000
    private const val REPLY_TIMEOUT_MS = 10_000
    private const val TTS_TIMEOUT_MS = 180_000
    private const val JSON_LIMIT = 64 * 1024
    private val allowedSuffixes = setOf(".wav", ".m4a", ".mp3", ".ogg", ".opus", ".flac", ".aac")

    data class TtsResult(val pending: Boolean, val bytes: ByteArray? = null, val suffix: String? = null,
                         val segment: Int = 0, val segmentCount: Int = 1)

    fun submitAudio(token: String, wav: ByteArray, scope: ScopedVoiceCancellation.Scope,
                    beforeUpload: () -> Unit = {}, onConnection: (HttpURLConnection?) -> Unit): JSONObject {
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        require(wav.isNotEmpty() && wav.size <= VoiceBridgeProtocol.MAX_HTTP_BODY) { "audio_size_invalid" }
        val connection = open("/audio", "POST", token, AUDIO_TIMEOUT_MS)
        try {
            onConnection(connection)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("X-Audio-Format", ".wav")
            connection.setRequestProperty("X-Voice-Ingress-Operation", scope.ingressOperationId)
            connection.setRequestProperty("X-Voice-Session-Generation", scope.sessionGeneration)
            connection.setFixedLengthStreamingMode(wav.size)
            beforeUpload() // Last local admission boundary before connecting/writing the body.
            connection.outputStream.use { it.write(wav) }
            return readJson(connection, setOf(200))
        } finally {
            onConnection(null)
            connection.disconnect()
        }
    }

    fun pollReply(token: String, requestId: String, afterSeq: Long = 0, onConnection: (HttpURLConnection?) -> Unit): JSONObject {
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        val path = VoiceBridgeProtocol.replyUrl(requestId)?.removePrefix(VoiceBridgeProtocol.BASE_URL)
            ?: throw IllegalArgumentException("invalid_request_id")
        val connection = open(path + "&protocol=4&after_seq=$afterSeq", "GET", token, REPLY_TIMEOUT_MS)
        onConnection(connection)
        try {
            return readJson(connection, setOf(200))
        } finally {
            onConnection(null)
            connection.disconnect()
        }
    }

    fun fetchTts(token: String, requestId: String, segment: Int = 0, onConnection: (HttpURLConnection?) -> Unit): TtsResult {
        require(segment in 0 until ReplyAudioSequence.MAX_SEGMENTS) { "invalid_audio_segment" }
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        val path = VoiceBridgeProtocol.ttsUrl(requestId)?.removePrefix(VoiceBridgeProtocol.BASE_URL)
            ?: throw IllegalArgumentException("invalid_request_id")
        val connection = open(path + "&protocol=5&segment=$segment", "GET", token, TTS_TIMEOUT_MS)
        onConnection(connection)
        try {
            return readTts(connection, segment)
        } finally {
            onConnection(null)
            connection.disconnect()
        }
    }

    internal fun readTts(connection: HttpURLConnection, requestedSegment: Int): TtsResult {
        val code = connection.responseCode
        if (code == 202) {
            readJson(connection, setOf(202))
            return TtsResult(pending = true)
        }
        if (code != 200) throw readError(connection, code)
        val suffix = connection.getHeaderField("X-Audio-Format")?.lowercase()
        if (suffix !in allowedSuffixes) throw BridgeException("tts_format_invalid")
        val indexHeader = connection.getHeaderField("X-Audio-Segment")
        val countHeader = connection.getHeaderField("X-Audio-Segments")
        val legacy = indexHeader == null && countHeader == null
        val index = if (legacy) 0 else indexHeader?.toIntOrNull()
        val count = if (legacy) 1 else countHeader?.toIntOrNull()
        if (index != requestedSegment || count == null || count !in 1..ReplyAudioSequence.MAX_SEGMENTS || index !in 0 until count)
            throw BridgeException("tts_segment_invalid")
        val audio = readBounded(connection.inputStream, VoiceBridgeProtocol.MAX_REPLY_AUDIO)
        if (audio.isEmpty()) throw BridgeException("tts_audio_empty")
        return TtsResult(pending = false, bytes = audio, suffix = suffix, segment = index, segmentCount = count)
    }

    fun confirmation(token: String, requestId: String, actionId: String): JSONObject {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && actionId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val c=open("/confirmation?request_id=$requestId&action_id=$actionId","GET",token,REPLY_TIMEOUT_MS)
        return try { readJson(c,setOf(200,409)) } finally { c.disconnect() }
    }
    fun armConfirmation(token: String, requestId: String, actionId: String): JSONObject {
        val body=JSONObject().put("request_id",requestId).put("action_id",actionId).toString().toByteArray()
        val c=open("/confirmation/arm","POST",token,REPLY_TIMEOUT_MS)
        return try {
            c.doOutput=true; c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }; readJson(c,setOf(200))
        } finally { body.fill(0); c.disconnect() }
    }
    fun confirmationAudio(token: String, requestId: String, actionId: String, nonce: String, wav: ByteArray,
                          onConnection: (HttpURLConnection?) -> Unit): JSONObject {
        val c=open("/confirmation/audio","POST",token,AUDIO_TIMEOUT_MS)
        onConnection(c)
        return try {
            c.doOutput=true; c.setRequestProperty("Content-Type","audio/wav")
            c.setRequestProperty("X-Voice-Request",requestId); c.setRequestProperty("X-Voice-Action",actionId)
            c.setRequestProperty("X-Voice-Nonce",nonce); c.setFixedLengthStreamingMode(wav.size)
            c.outputStream.use { it.write(wav) }; readJson(c,setOf(200))
        } finally { onConnection(null); c.disconnect() }
    }

    fun capabilities(token: String): JSONObject {
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        val c = open("/v1/capabilities", "GET", token, REPLY_TIMEOUT_MS)
        return try { readJson(c, setOf(200)) }
        catch (_: IOException) { throw BridgeException("HOST_CAPABILITIES_UNAVAILABLE") }
        finally { c.disconnect() }
    }

    fun learningStatus(token: String): JSONObject {
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        val c = open("/v1/learning-status", "GET", token, REPLY_TIMEOUT_MS)
        return try { readJson(c, setOf(200)) }
        catch (_: IOException) { throw BridgeException("LEARNING_STATUS_UNAVAILABLE") }
        finally { c.disconnect() }
    }

    internal fun permitsVoiceExecution(value: JSONObject): Boolean {
        val execution = value.optJSONObject("voice_execution") ?: return false
        // Preserve JSON types and missing keys. Only explicit JSON null becomes Java null.
        val fields = execution.keys().asSequence().associateWith { key ->
            execution.opt(key).let { if (it === JSONObject.NULL) null else it }
        }
        return SetupReadiness.permitsVoiceExecution(value.opt("schema_version"), value.opt("ok"),
            value.opt("strict_voice"), fields)
    }

    fun requireVoiceExecution(token: String) {
        if (!permitsVoiceExecution(capabilities(token)))
            throw BridgeException("HOST_ROUTE_CONTRACT_UNAVAILABLE")
    }

    fun streamingStatus(token: String): JSONObject {
        val c = open("/status", "GET", token, REPLY_TIMEOUT_MS)
        return try { readJson(c, setOf(200)).getJSONObject("streaming") }
        finally { c.disconnect() }
    }

    fun ready(token: String): Boolean {
        requireVoiceExecution(token)
        val connection = open("/status", "GET", token, REPLY_TIMEOUT_MS)
        return try { val value=readJson(connection,setOf(200)); value.optBoolean("listening") && value.optInt("protocol")>=3 }
        finally { connection.disconnect() }
    }

    fun cancellationSession(token: String): String {
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        val connection = open("/status", "GET", token, REPLY_TIMEOUT_MS)
        try {
            val response = readJson(connection, setOf(200))
            val generation = response.optString("session_generation")
            if (!response.optBoolean("listening") || response.optBoolean("cancel_cleanup_blocked") ||
                !VoiceBridgeProtocol.validRequestId(generation)) throw BridgeException("cancel_scope_unavailable")
            return generation
        } finally { connection.disconnect() }
    }

    fun cancelTurn(token: String, scope: ScopedVoiceCancellation.Scope) {
        if (!revokeTurn(token, scope)) throw BridgeException("cancel_cleanup_unconfirmed")
    }

    /** Revocation is not settlement. Only the duplex IO worker uses this split phase. */
    private fun revokeTurn(token: String, scope: ScopedVoiceCancellation.Scope): Boolean {
        require(VoiceBridgeProtocol.validToken(token)) { "invalid_voice_credential" }
        val key = if (scope.requestId == null) "ingress_operation_id" else "request_id"
        val id = scope.requestId ?: scope.ingressOperationId
        val body = JSONObject().put("session_generation", scope.sessionGeneration).put(key, id).toString().toByteArray()
        val connection = open("/cancel", "POST", token, REPLY_TIMEOUT_MS)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val response = readJson(connection, setOf(200))
            if (response.opt("ok") != true || response.opt("revoked") != true ||
                response.optString("session_generation") != scope.sessionGeneration || response.optString(key) != id)
                throw BridgeException("cancel_scope_unconfirmed")
            return response.opt("cleanup_confirmed") == true
        } finally { body.fill(0); connection.disconnect() }
    }

    /** Worker only: one cancel, then bounded read-only exact-run settlement polls.
     * Missing API/UNKNOWN/mismatch/timeout NEVER becomes CLEAN or a retry. */
    fun cancelAndAwaitTerminal(token: String, scope: ScopedVoiceCancellation.Scope,
                               current: () -> Boolean): Boolean = cancelAndAwaitSettlement(token, scope, current)

    /** Cleanup duty for the captured old request survives OFF. This grants no effect authority. */
    fun cancelAndAwaitCleanup(token: String, scope: ScopedVoiceCancellation.Scope): Boolean {
        // Reservation is process-retained. UNKNOWN cannot be healed by a late join.
        val call = synchronized(cleanupLock) {
            if (cleanupCall != null) return false
            CleanupCall(token, scope).also { cleanupCall = it }
        }
        val clean = call.await()
        synchronized(cleanupLock) { if (clean && cleanupCall === call) cleanupCall = null }
        return clean
    }

    private val cleanupLock = Any()
    private var cleanupCall: CleanupCall? = null
    internal fun cleanupSnapshot(): List<Boolean> = synchronized(cleanupLock) {
        cleanupCall?.snapshot() ?: listOf(false, false, false, false, true)
    }

    /** Public HttpURLConnection has no hard write/header cancellation guarantee.
     * One owned I/O worker and at most one closer are retained; never replaced on UNKNOWN.
     * Disconnect is an attempted interruption, NOT evidence of native worker termination. */
    private class CleanupCall(val token: String, val scope: ScopedVoiceCancellation.Scope) {
        // Includes cancel, all polls, stream closure and actual worker join.
        private val deadline = System.nanoTime() + 8_000_000_000L
        private val lock = Any()
        @Volatile private var unknown = false
        @Volatile private var clean = false
        private var connection: HttpURLConnection? = null
        private var closing = false
        private var closeAttempted = false
        @Volatile private var closer: Thread? = null
        private val worker = Thread({
            try { clean = exchange() }
            catch (_: Exception) { unknown = true }
            finally {
                try { closeOwned() } catch (_: Exception) { unknown = true }
            }
        }, "voice-cleanup-io")

        fun ownsCurrentThread(): Boolean = Thread.currentThread() === worker
        fun snapshot(): List<Boolean> = synchronized(lock) {
            listOf(true, worker.isAlive, closer?.isAlive == true, unknown, connection == null && !closing)
        }
        private fun remaining(c: HttpURLConnection? = null): Int {
            val left = deadline - System.nanoTime()
            if (unknown || Thread.currentThread().isInterrupted || left <= 0)
                throw IOException("cleanup_deadline_or_interrupt")
            val ms = minOf(1000L, maxOf(1L, left / 1_000_000L)).toInt()
            c?.let { it.connectTimeout = ms; it.readTimeout = ms }
            return ms
        }
        private fun closeOwned() {
            val c = synchronized(lock) {
                if (closing || closeAttempted) return
                val value = connection ?: return
                closing = true
                closeAttempted = true
                value
            }
            // Never under the reservation or service owner lock; exactly one attempt.
            try {
                c.disconnect()
                synchronized(lock) { connection = null }
            } catch (failure: Exception) { unknown = true; throw failure }
            finally { synchronized(lock) { closing = false } }
        }
        private fun expire() {
            unknown = true // BEFORE any disconnect or late CLEAN response
            worker.interrupt()
            synchronized(lock) {
                if (connection != null && !closing && !closeAttempted && closer == null) {
                    closer = Thread({
                        try { closeOwned() } catch (_: Exception) { unknown = true }
                    }, "voice-cleanup-close").also { it.start() }
                }
            }
        }
        fun await(): Boolean {
            var interrupted = false
            try {
                worker.start()
                while (worker.isAlive) {
                    val left = deadline - System.nanoTime()
                    // Reserve the final 200ms for interrupt/disconnect and actual joins.
                    if (left <= 200_000_000L) { expire(); break }
                    worker.join(minOf(20L, maxOf(1L, (left - 200_000_000L) / 1_000_000L)))
                }
            } catch (_: InterruptedException) { interrupted = true; expire() }
            catch (_: Exception) { unknown = true; expire() }
            finally {
                for (thread in listOf(worker, closer)) {
                    if (thread != null && thread.isAlive) {
                        val left = deadline - System.nanoTime()
                        if (left > 0) try { thread.join(maxOf(1L, left / 1_000_000L)) }
                        catch (_: InterruptedException) { interrupted = true; unknown = true }
                    }
                }
                synchronized(lock) {
                    if (worker.isAlive || closer?.isAlive == true || connection != null || closing ||
                        System.nanoTime() >= deadline || !clean) unknown = true
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            return !unknown && clean && !worker.isAlive && closer?.isAlive != true
        }
        private fun post(path: String, body: ByteArray): JSONObject {
            remaining()
            val c = open(path, "POST", token, remaining())
            synchronized(lock) {
                // Publish ownership even if expiry won the open race. Worker finally closes it.
                check(connection == null && !closing)
                connection = c
                closeAttempted = false
            }
            try {
                remaining(c)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(body.size)
                remaining(c)
                c.outputStream.use { stream -> remaining(c); stream.write(body); remaining(c) }
                remaining(c)
                val code = c.responseCode
                remaining(c)
                val input = if (code < 400) c.inputStream else c.errorStream
                    ?: throw IOException("cleanup_http")
                val bytes = input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    try {
                        while (true) {
                            remaining(c)
                            val count = stream.read(buffer)
                            remaining(c)
                            if (count < 0) break
                            if (count == 0 || output.size() + count > JSON_LIMIT)
                                throw IOException("cleanup_body_limit")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } finally { buffer.fill(0) }
                }
                try {
                    remaining(c)
                    if (code != 200) throw IOException("cleanup_http")
                    return JSONObject(String(bytes, Charsets.UTF_8))
                } finally { bytes.fill(0) }
            } finally { closeOwned() }
        }
        private fun exchange(): Boolean {
            require(VoiceBridgeProtocol.validToken(token))
            if (scope.requestId == null) return false
            val body = JSONObject().put("session_generation", scope.sessionGeneration)
                .put("request_id", scope.requestId).toString().toByteArray()
            try {
                val cancel = post("/cancel", body) // exactly once; no retry or new target
                if (cancel.opt("ok") != true || cancel.opt("revoked") != true ||
                    cancel.optString("session_generation") != scope.sessionGeneration ||
                    cancel.optString("request_id") != scope.requestId) return false
                val gate = DuplexTurnHandoff.TerminalGate(scope.sessionGeneration, scope.requestId)
                repeat(32) {
                    remaining()
                    val response = post("/cancel/status", body)
                    val result = gate.observe(response.opt("ok") == true,
                        response.opt("session_generation") as? String ?: "", response.opt("request_id") as? String ?: "",
                        response.opt("run_id") as? String ?: "", response.opt("terminal") as? String ?: "",
                        response.opt("cleanup_confirmed") == true)
                    remaining()
                    if (result == DuplexTurnHandoff.Terminal.UNKNOWN) return false
                    if (result == DuplexTurnHandoff.Terminal.CLEAN) return true
                    Thread.sleep(minOf(100, remaining()).toLong())
                }
                return false
            } finally { body.fill(0) }
        }
    }

    private fun cancelAndAwaitSettlement(token: String, scope: ScopedVoiceCancellation.Scope,
                                         authority: (() -> Boolean)?): Boolean {
        if (scope.requestId == null) return false
        // OFF can race the worker start. Still revoke the captured OLD target; current()
        // gates handoff, not the obligation to send that target's cancellation.
        revokeTurn(token, scope) // cleanup=false is expected while the old worker is stopping
        val gate = DuplexTurnHandoff.TerminalGate(scope.sessionGeneration, scope.requestId)
        val deadline = System.nanoTime() + 8_000_000_000L
        val body = JSONObject().put("session_generation", scope.sessionGeneration)
            .put("request_id", scope.requestId).toString().toByteArray()
        try {
            repeat(32) {
                if (authority?.invoke() == false || System.nanoTime() >= deadline) return false
                val c = open("/cancel/status", "POST", token, 1000)
                c.connectTimeout = 1000
                val response = try {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                    readJson(c, setOf(200))
                } finally { c.disconnect() }
                val result = gate.observe(response.opt("ok") == true,
                    response.opt("session_generation") as? String ?: "", response.opt("request_id") as? String ?: "",
                    response.opt("run_id") as? String ?: "", response.opt("terminal") as? String ?: "",
                    response.opt("cleanup_confirmed") == true)
                if (result == DuplexTurnHandoff.Terminal.UNKNOWN) return false
                if (result == DuplexTurnHandoff.Terminal.CLEAN)
                    return authority?.invoke() != false && System.nanoTime() < deadline
                Thread.sleep(100)
            }
            return false
        } finally { body.fill(0) }
    }

    fun stopBridge(token: String, onConnection: (HttpURLConnection?) -> Unit): Boolean {
        if (!VoiceBridgeProtocol.validToken(token)) return false
        val connection = open("/off", "POST", token, REPLY_TIMEOUT_MS)
        onConnection(connection)
        return try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.use { }
            val response = readJson(connection, setOf(200))
            response.opt("ok") == true && response.opt("listening") == false && response.opt("cleanup_confirmed") == true
        } catch (_: Exception) {
            false
        } finally {
            onConnection(null)
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String, token: String, readTimeout: Int): HttpURLConnection {
        synchronized(cleanupLock) {
            check(cleanupCall == null || cleanupCall!!.ownsCurrentThread()) { "cleanup_reservation_retained" }
        }
        require(path.startsWith("/") && !path.startsWith("//")) { "invalid_bridge_path" }
        val connection = URL(VoiceBridgeProtocol.BASE_URL + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeout
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json, audio/*")
        return connection
    }

    private fun readJson(connection: HttpURLConnection, accepted: Set<Int>): JSONObject {
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream
                     else connection.errorStream ?: throw BridgeException("bridge_http_$code")
        val bytes = readBounded(stream, JSON_LIMIT)
        val value = try { JSONObject(String(bytes, Charsets.UTF_8)) }
                     catch (_: Exception) { throw BridgeException("bridge_response_invalid") }
        if (code !in accepted) throw responseError(value, code)
        return value
    }

    private fun responseError(value: JSONObject, code: Int): BridgeException {
        if (code == 502) {
            val facts = VoiceSttOutcome.failure(value)
            if (facts != null) return BridgeException("stt_" + facts.reason, facts.reason, facts.cleanupConfirmed)
        }
        // Error bodies are untrusted. Preserve only existing fixed protocol errors.
        val allowed = setOf("transcription_failed", "bridge_off", "unauthorized", "audio_busy",
            "voice_cancelled", "cancel_cleanup_unconfirmed", "ingress_session_stale",
            "ingress_revoked_or_reused", "voice_turn_in_progress", "conversation_busy",
            "tts_failed", "tts_not_ready", "request_not_found", "invalid_request_id")
        val error = value.opt("error") as? String
        return BridgeException(if (error in allowed) error!! else "bridge_http_$code")
    }

    private fun readError(connection: HttpURLConnection, code: Int): BridgeException {
        val stream = connection.errorStream ?: return BridgeException("bridge_http_$code")
        return try {
            responseError(JSONObject(String(readBounded(stream, JSON_LIMIT), Charsets.UTF_8)), code)
        } catch (_: Exception) {
            BridgeException("bridge_http_$code")
        }
    }

    private fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maximum, 64 * 1024))
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > maximum) throw BridgeException("response_too_large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    class BridgeException(val category: String, val sttReason: String? = null,
                          val sttCleanupConfirmed: Boolean? = null) : IOException(category)
}
