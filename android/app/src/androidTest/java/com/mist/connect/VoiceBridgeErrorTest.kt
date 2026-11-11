package com.mist.connect
import org.junit.Test
import org.junit.Assert.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.IOException
import org.json.JSONObject
class VoiceBridgeErrorTest {
    @Test fun acceptedConflictReadsErrorStream() {
        val c=object: HttpURLConnection(URL("http://127.0.0.1/fixture")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 409
            override fun getInputStream(): java.io.InputStream = throw IOException("HTTP conflict")
            override fun getErrorStream() = ByteArrayInputStream("{\"ok\":false,\"error\":\"confirmation_pending\"}".toByteArray())
        }
        val method=VoiceBridgeClient::class.java.getDeclaredMethod("readJson",HttpURLConnection::class.java,Set::class.java)
        method.isAccessible=true
        val value=method.invoke(VoiceBridgeClient,c,setOf(409)) as JSONObject
        assertFalse(value.getBoolean("ok"));assertEquals("confirmation_pending",value.getString("error"))
    }
}
