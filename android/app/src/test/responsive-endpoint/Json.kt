package org.json
import com.google.gson.*
class JSONObject(private val obj:JsonObject) {
 constructor(text:String):this(JsonParser.parseString(text).asJsonObject)
 fun optString(key:String):String = obj.get(key)?.let { if(it.isJsonNull) "" else it.asString } ?: ""
 fun optDouble(key:String, fallback:Double):Double = obj.get(key)?.asDouble ?: fallback
 fun optJSONArray(key:String):JSONArray? = obj.get(key)?.asJsonArray?.let { JSONArray(it) }
}
class JSONArray(private val array:JsonArray) {
 fun length()=array.size()
 fun optJSONObject(index:Int):JSONObject? = if(index in 0 until array.size()) JSONObject(array[index].asJsonObject) else null
}
