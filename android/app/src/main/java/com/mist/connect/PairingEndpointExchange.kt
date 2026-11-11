package com.mist.connect

import org.json.JSONObject

/** Protocol algorithm only; no owner or credential store. Injectable for JVM tests. */
internal object PairingEndpointExchange {
    fun resolve(request: JSONObject, resumed: Boolean, call: (String, JSONObject) -> JSONObject): JSONObject {
        if (!resumed) {
            // Even an apparent success is read back from the exact operation ledger.
            try { call("/v1/pairing/endpoint", request) } catch (_: Exception) { /* lookup below */ }
        }
        val result = call("/v1/pairing/result", JSONObject().put("operation_id", request.getString("operation_id")))
        require(result.keys().asSequence().toSet() == setOf("ok", "operation_id", "state", "revision", "ui_url"))
        require(result.opt("ok") == true && result.getString("operation_id") == request.getString("operation_id"))
        val state = result.getString("state")
        require(state in setOf("committed", "rejected", "unknown", "not_found"))
        if (state == "not_found") {
            require(result.isNull("revision") && result.isNull("ui_url"))
        } else {
            require(result.opt("revision") is Int || result.opt("revision") is Long)
            require(result.getLong("revision") == request.getLong("expected_revision") + if (state == "committed") 1L else 0L)
            require(result.getString("ui_url") == request.getString("ui_url"))
        }
        return result
    }
}
