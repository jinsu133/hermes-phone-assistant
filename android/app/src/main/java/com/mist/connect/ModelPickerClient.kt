package com.mist.connect

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Timer
import java.util.TimerTask

/** Only authenticated GET. No save/stop/on/command/injection method exists. */
class ModelPickerClient : Closeable {
    private val gate = Any()
    private var closed = false
    private var active: HttpURLConnection? = null

    fun load(token: String): ModelPickerSnapshot {
        require(VoiceBridgeProtocol.validToken(token)) { "MODEL_PICKER_UNPAIRED" }
        val connection = URL(VoiceBridgeProtocol.BASE_URL + "/v1/model-picker")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        synchronized(gate) {
            if (closed || active != null) { connection.disconnect(); throw IOException("MODEL_PICKER_CLOSED") }
            active = connection
        }
        val deadline = System.nanoTime() + 12_000_000_000L
        // Absolute deadline, not just per-read idle timeout; also disconnect on Activity destruction.
        val timer = Timer("model-picker-deadline", true)
        timer.schedule(object : TimerTask() { override fun run() { connection.disconnect() } }, 12000L)
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != 200) throw IOException("MODEL_PICKER_UNAVAILABLE")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)
                        throw IOException("MODEL_PICKER_TIMEOUT")
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw IOException("MODEL_PICKER_TOO_LARGE")
                    output.write(buffer, 0, count)
                }
            }
            val result = parse(JSONObject(output.toString("UTF-8")))
            synchronized(gate) { if (closed) throw IOException("MODEL_PICKER_CLOSED") }
            if (System.nanoTime() >= deadline) throw IOException("MODEL_PICKER_TIMEOUT")
            return result
        } finally {
            timer.cancel()
            connection.disconnect()
            synchronized(gate) { if (active === connection) active = null }
        }
    }

    override fun close() {
        val old = synchronized(gate) { closed = true; active.also { active = null } }
        old?.disconnect()
    }

    companion object {
        private fun fail(): Nothing = throw IllegalArgumentException("MODEL_PICKER_SCHEMA_INVALID")
        private fun bool(o: JSONObject, key: String): Boolean = o.opt(key) as? Boolean ?: fail()
        private fun str(o: JSONObject, key: String): String = o.opt(key) as? String ?: fail()
        private fun nullableString(o: JSONObject, key: String): String? {
            if (!o.has(key)) fail()
            return if (o.opt(key) === JSONObject.NULL) null else str(o, key)
        }
        private fun nullableBool(o: JSONObject, key: String): Boolean? {
            if (!o.has(key)) fail()
            return if (o.opt(key) === JSONObject.NULL) null else bool(o, key)
        }
        private fun revision(value: Any?): Long {
            val number = when (value) { is Int -> value.toLong(); is Long -> value; else -> fail() }
            if (number < 0) fail()
            return number
        }
        private fun strings(array: JSONArray): List<String> = (0 until array.length()).map {
            array.opt(it) as? String ?: fail()
        }

        fun parse(o: JSONObject): ModelPickerSnapshot {
            if (o.opt("schema_version") != 2 || !bool(o, "ok") || bool(o, "app_write_supported") ||
                bool(o, "strict_physical_route") || bool(o, "model_effort_support_known") ||
                str(o, "selection_semantics") != "request_preferences" || str(o, "mode") != "runs" ||
                str(o, "operator_command") != "phone-execution" || str(o, "operator_action") != "select") fail()
            val array = o.getJSONArray("providers")
            if (array.length() > 256) fail()
            var totalModels = 0
            val providers = (0 until array.length()).map { i ->
                val p = array.getJSONObject(i)
                val aliases = strings(p.getJSONArray("aliases"))
                val nonselectable = strings(p.getJSONArray("nonselectable_aliases"))
                if (aliases.size > 64 || aliases.any { !ModelPickerContract.validMetadataAlias(it) } ||
                    nonselectable != aliases.filter { !ModelPickerContract.validIdentifier(it) }) fail()
                if (!p.has("unavailable_models")) fail()
                val unavailable = if (p.opt("unavailable_models") === JSONObject.NULL) null
                    else strings(p.getJSONArray("unavailable_models"))
                if (unavailable != null && (unavailable.size > 20000 ||
                    unavailable.any { !ModelPickerContract.validIdentifier(it) })) fail()
                val unavailableSet = unavailable?.toSet()
                val pending = nullableBool(p, "free_tier_pending")
                val models = p.getJSONArray("models")
                totalModels += models.length()
                if (totalModels > 20000) fail()
                ModelPickerProvider(str(p, "id"), aliases, str(p, "auth_readiness"),
                    (0 until models.length()).map { j ->
                        val m = models.getJSONObject(j)
                        if (!m.has("supported_efforts") || m.opt("supported_efforts") !== JSONObject.NULL) fail()
                        val id = str(m, "id")
                        val locked = nullableBool(m, "unavailable")
                        if (locked != unavailableSet?.contains(id)) fail()
                        ModelPickerModel(id, nullableBool(m, "reasoning_hint"), nullableBool(m, "can_disable_reasoning"), locked)
                    }, nonselectable, unavailable, pending)
            }
            if (providers.map { it.id }.distinct().size != providers.size || providers.any { p ->
                    !ModelPickerContract.validIdentifier(p.id) ||
                    p.auth !in setOf("ready", "not_ready", "unknown") ||
                    p.models.map { it.id }.distinct().size != p.models.size ||
                    p.models.any { !ModelPickerContract.validIdentifier(it.id) } }) fail()
            val efforts = strings(o.getJSONArray("request_efforts"))
            if (efforts.distinct().size != efforts.size || efforts.any {
                    it !in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra") }) fail()
            val selection = o.getJSONObject("selection")
            if (selection.keys().asSequence().toSet() != setOf("provider", "model", "reasoning_effort")) fail()
            val selected = ModelPickerSelection(nullableString(selection, "provider"),
                nullableString(selection, "model"), nullableString(selection, "reasoning_effort"))
            if (listOf(selected.provider, selected.model).any { it != null && !ModelPickerContract.validIdentifier(it) } ||
                (selected.effort != null && selected.effort !in efforts)) fail()
            if (!o.has("live_revision")) fail()
            val live = if (o.opt("live_revision") === JSONObject.NULL) null else revision(o.opt("live_revision"))
            val saved = revision(o.opt("revision"))
            val matches = bool(o, "live_binding_matches")
            if (matches && live != saved) fail()
            return ModelPickerSnapshot(saved, live, matches, bool(o, "operator_write_ready"),
                str(o, "host_provider"), str(o, "host_model"), providers, efforts, selected)
        }
    }
}
