package org.json

import com.google.gson.*

/** Test-only JSON double, following the existing Gson fixture harness. Not Android JSON. */
class JSONObject(private val obj: JsonObject = JsonObject()) {
    constructor(text: String) : this(JsonParser.parseString(text).asJsonObject)
    companion object { @JvmField val NULL = Any() }
    fun keys(): MutableIterator<String> = obj.keySet().iterator()
    fun opt(key: String): Any? {
        val value = obj.get(key) ?: return null
        if (value.isJsonNull) return NULL
        if (value.isJsonObject) return JSONObject(value.asJsonObject)
        if (!value.isJsonPrimitive) return value
        val primitive = value.asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        if (primitive.isString) return primitive.asString
        val raw = primitive.asString
        return raw.toIntOrNull() ?: raw.toLongOrNull() ?: raw.toDouble()
    }
    fun optJSONObject(key: String): JSONObject? = opt(key) as? JSONObject
    fun getJSONObject(key: String): JSONObject = optJSONObject(key) ?: error("missing object")
    fun optString(key: String, fallback: String = ""): String = opt(key)?.let { if (it === NULL) fallback else it.toString() } ?: fallback
    fun optBoolean(key: String): Boolean = opt(key) == true
    fun optInt(key: String): Int = (opt(key) as? Number)?.toInt() ?: 0
    fun put(key: String, value: Any?): JSONObject {
        when (value) {
            null -> obj.remove(key)
            NULL -> obj.add(key, JsonNull.INSTANCE)
            is JSONObject -> obj.add(key, value.obj)
            is Boolean -> obj.addProperty(key, value)
            is Number -> obj.addProperty(key, value)
            else -> obj.addProperty(key, value.toString())
        }
        return this
    }
    override fun toString(): String = obj.toString()
}
