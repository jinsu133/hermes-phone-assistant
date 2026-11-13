package com.mist.connect

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit user control-plane action. Never rotates keys or starts a voice owner. */
internal object PairingEndpointUpdate {
    private val busy = AtomicBoolean(false)

    fun run(context: Context): String {
        check(busy.compareAndSet(false, true)) { "pairing_busy" }
        try {
            check(!VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId.isEmpty()) { "voice_off_required" }
            val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val token = prefs.getString("voice_token", "") ?: ""
            val uiToken = prefs.getString("mcp_token", "") ?: ""
            val pairing = prefs.getString("unified_pairing_id", "") ?: ""
            val instance = prefs.getString("phone_instance_id", "") ?: ""
            require(UUID.fromString(pairing).toString() == pairing && UUID.fromString(instance).toString() == instance)
            val old = prefs.getString("pairing_endpoint_pending", null)
            val revision = prefs.getLong("unified_pairing_revision", 1L)
            val request = if (old != null) JSONObject(old) else JSONObject()
                .put("operation_id", UUID.randomUUID().toString()).put("expected_revision", revision)
                .put("pairing_id", pairing).put("device_instance_id", instance)
                .put("ui_url", checkNotNull(McpService.instance).boundMcpUrl())
            // Durable intent BEFORE the only POST attempt. After process death or
            // lost ACK, the same button is lookup-only, never automatic replay.
            if (old == null) check(prefs.edit().putString("pairing_endpoint_pending", request.toString()).commit())
            fun live(): Boolean = token == prefs.getString("voice_token", "") &&
                uiToken == prefs.getString("mcp_token", "") && pairing == prefs.getString("unified_pairing_id", "") &&
                instance == prefs.getString("phone_instance_id", "") &&
                !VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId.isEmpty()
            val result = PairingEndpointExchange.resolve(request, old != null) { path, payload ->
                check(live())
                val transport = PairingHttp { SystemClock.elapsedRealtime() }
                val data = payload.toString().toByteArray(Charsets.UTF_8)
                try {
                    val raw = transport.post(path, token, data) { live() }
                    try { JSONObject(String(raw, Charsets.UTF_8)) } finally { raw.fill(0) }
                } finally { data.fill(0); transport.close() }
            }
            check(live() && revision == prefs.getLong("unified_pairing_revision", 1L))
            return when (result.getString("state")) {
                "committed" -> {
                    check(prefs.edit().putLong("unified_pairing_revision", result.getLong("revision"))
                        .remove("pairing_endpoint_pending").commit())
                    AppText.get(R.string.rt_endpoint_committed, "MCP 주소 갱신 확인됨 · 음성 키와 5001 주소는 그대로 유지됩니다")
                }
                "rejected" -> {
                    check(prefs.edit().remove("pairing_endpoint_pending").commit())
                    AppText.get(R.string.rt_endpoint_rejected, "주소 갱신이 거절됐습니다 · 기존 연결 유지")
                }
                else -> AppText.get(R.string.rt_endpoint_unknown, "주소 갱신 결과 미확정 · 자동 재전송 없이 같은 작업의 결과만 확인합니다")
            }
        } finally { busy.set(false) }
    }
}
