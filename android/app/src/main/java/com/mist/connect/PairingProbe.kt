package com.mist.connect

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Authenticated control-plane probe. No mic, UI read, credential entry or service start. */
internal object PairingProbe {
    private val active = AtomicReference<PairingHttp?>(null)
    fun cancel() { active.get()?.close() }

    fun check(context: Context, args: JSONObject, live: () -> Boolean): JSONObject =
        checkProof(context, args, null, live)

    fun checkEndpoint(context: Context, args: JSONObject, boundUrl: String, live: () -> Boolean): JSONObject =
        checkProof(context, args, boundUrl, live)

    private fun checkProof(context: Context, args: JSONObject, boundUrl: String?, live: () -> Boolean): JSONObject {
        val extra = if (boundUrl == null) emptySet() else setOf("operation_id", "expected_revision", "ui_url")
        require(args.keys().asSequence().toSet() == setOf("nonce", "pairing_id", "revision", "instance_id") + extra)
        require(args.opt("nonce") is String && args.opt("pairing_id") is String && args.opt("instance_id") is String)
        require(args.opt("revision") is Int || args.opt("revision") is Long)
        val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val nonce = args.getString("nonce")
        val pairing = args.getString("pairing_id")
        val revision = args.getLong("revision")
        val instance = args.getString("instance_id")
        val currentRevision = prefs.getLong("unified_pairing_revision", 1L)
        if (boundUrl != null) {
            require(args.opt("expected_revision") is Int || args.opt("expected_revision") is Long)
            require(currentRevision < Long.MAX_VALUE && args.getLong("expected_revision") == currentRevision)
            require(args.getString("ui_url") == boundUrl)
            val operation = args.getString("operation_id")
            require(java.util.UUID.fromString(operation).toString() == operation)
            // Only the explicitly prepared local operation can answer this challenge.
            val pending = JSONObject(prefs.getString("pairing_endpoint_pending", "") ?: "")
            require(pending.getString("operation_id") == operation && pending.getString("ui_url") == boundUrl &&
                    pending.getLong("expected_revision") == currentRevision)
            check(!VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId.isEmpty())
        }
        require(revision == currentRevision + (if (boundUrl == null) 0L else 1L) && pairing == prefs.getString("unified_pairing_id", "") &&
                instance == prefs.getString("phone_instance_id", "")) { "pairing_changed" }
        val voiceKey = prefs.getString("voice_token", "") ?: ""
        val uiKey = prefs.getString("mcp_token", "") ?: ""
        val signed = PairingProof.sign(voiceKey, nonce, pairing, revision, instance)
        check(live()) { "pairing_stopped" }
        val transport = PairingHttp { SystemClock.elapsedRealtime() }
        check(active.compareAndSet(null, transport)) { "pairing_busy" }
        try {
            val raw = transport.status(voiceKey) { live() }
            val state = try { JSONObject(String(raw, Charsets.UTF_8)) } finally { raw.fill(0) }
            check(state.opt("listening") == true && state.optInt("protocol") == 5) { "pairing_voice_not_ready" }
            check(live() && voiceKey == prefs.getString("voice_token", "") && uiKey == prefs.getString("mcp_token", "") &&
                    pairing == prefs.getString("unified_pairing_id", "") && instance == prefs.getString("phone_instance_id", "")) { "pairing_changed" }
            check(currentRevision == prefs.getLong("unified_pairing_revision", 1L)) { "pairing_changed" }
            if (boundUrl != null) {
                check(!VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId.isEmpty())
                check(McpService.instance?.boundMcpUrl() == boundUrl)
                val pending = JSONObject(prefs.getString("pairing_endpoint_pending", "") ?: "")
                check(pending.getString("operation_id") == args.getString("operation_id"))
            }
            val result = JSONObject(args.toString()).put("voice_authenticated", true).put("proof", signed)
            return result
        } finally {
            transport.close()
            active.compareAndSet(transport, null)
        }
    }
}
