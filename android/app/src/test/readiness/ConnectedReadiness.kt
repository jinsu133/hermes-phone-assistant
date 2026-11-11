package com.mist.connect

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler

/** Compiled production client with explicit HTTP/JSON doubles; never opens a socket. */
private object Fixture {
    var payload = ""
    var status = 200
    val seen = mutableListOf<String>()
    val connections = mutableListOf<HttpURLConnection>()
    fun reset(body: String, code: Int = 200) {
        payload = body; status = code; seen.clear(); connections.clear()
    }
}
private class Response(url: URL) : HttpURLConnection(url) {
    var closed = false
    override fun connect() {}
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode(): Int {
        check(requestMethod == "GET")
        check(getRequestProperty("Authorization") == "Bearer " + "a".repeat(32))
        check(!instanceFollowRedirects && !useCaches)
        return Fixture.status
    }
    override fun getInputStream() = ByteArrayInputStream(
        (if (url.path == "/status") """{"listening":true,"protocol":5}""" else Fixture.payload).toByteArray())
    override fun getErrorStream() = ByteArrayInputStream(Fixture.payload.toByteArray())
}
fun main() {
    URL.setURLStreamHandlerFactory { protocol ->
        check(protocol == "http")
        object : URLStreamHandler() {
            override fun openConnection(url: URL): HttpURLConnection {
                check(url.host == "127.0.0.1" && url.port == 5001)
                check(url.path in setOf("/v1/capabilities", "/status"))
                Fixture.seen += url.path
                return Response(url).also { Fixture.connections += it }
            }
        }
    }
    val token = "a".repeat(32)
    val good = """{"schema_version":1,"ok":true,"strict_voice":false,"model_catalog":false,"voice_execution":{"mode":"runs","ready":true,"contract":"hermes-runs-v1","scoped_cancel":true,"strict_physical_route":false,"settings_cas_scope":"plugin","error":null}}"""
    check(VoiceBridgeClient.permitsVoiceExecution(JSONObject(good)))
    Fixture.reset(good)
    VoiceBridgeClient.requireVoiceExecution(token)
    check(Fixture.seen == listOf("/v1/capabilities"))
    check(Fixture.connections.all { (it as Response).closed })
    Fixture.reset(good)
    check(VoiceBridgeClient.ready(token))
    check(Fixture.seen == listOf("/v1/capabilities", "/status"))
    val denied = listOf(
        """{"schema_version":1,"ok":true,"strict_voice":true}""",
        good.replace("\"ready\":true", "\"ready\":false"),
        good.replace("\"ready\":true", "\"ready\":\"true\""),
        good.replace("\"strict_voice\":false", "\"strict_voice\":true"),
        good.replace("\"strict_voice\":false", "\"strict_voice\":\"false\""),
        good.replace("\"error\":null", "\"error\":\"HOST_ROUTE_CONTRACT_UNAVAILABLE\""),
        good.replace(",\"error\":null", ""),
        good.replace("\"scoped_cancel\":true", "\"scoped_cancel\":false"),
        good.replace("\"strict_physical_route\":false", "\"strict_physical_route\":true"),
        good.replace("\"settings_cas_scope\":\"plugin\"", "\"settings_cas_scope\":\"global\""),
        good.replace("\"voice_execution\":{", "\"ignored_execution\":{"),
        good.replace("\"schema_version\":1", "\"schema_version\":\"1\"")
    )
    for (body in denied) {
        check(!VoiceBridgeClient.permitsVoiceExecution(JSONObject(body)))
        Fixture.reset(body)
        try { VoiceBridgeClient.ready(token); error("capability must be denied") }
        catch (expected: VoiceBridgeClient.BridgeException) { check(expected.category == "HOST_ROUTE_CONTRACT_UNAVAILABLE") }
        check(Fixture.seen == listOf("/v1/capabilities")) // no status/work after denial
        check(Fixture.connections.all { (it as Response).closed })
    }
    for ((body, code) in listOf("not-json" to 200, "{}" to 401, "{}" to 503)) {
        Fixture.reset(body, code)
        try { VoiceBridgeClient.ready(token); error("HTTP/schema failure must be denied") }
        catch (expected: VoiceBridgeClient.BridgeException) { check(expected.category == "HOST_CAPABILITIES_UNAVAILABLE") }
        check(Fixture.seen == listOf("/v1/capabilities"))
        check(Fixture.connections.all { (it as Response).closed })
    }
    Fixture.reset(good)
    try { VoiceBridgeClient.requireVoiceExecution("invalid"); error("invalid token accepted") }
    catch (_: IllegalArgumentException) {}
    check(Fixture.seen.isEmpty())
    println("PASS compiled production VoiceBridgeClient: B positive + ${denied.size} negative payloads + 3 HTTP/JSON failures + credential denial; fixture HTTP/JSON only")
}
