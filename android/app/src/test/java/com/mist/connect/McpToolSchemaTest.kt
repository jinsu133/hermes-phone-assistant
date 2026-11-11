package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

class McpToolSchemaTest {
    @Suppress("UNCHECKED_CAST")
    private fun schema(name: String): Map<String, Any> {
        val klass = try { Class.forName("com.mist.connect.McpToolSchemas") }
        catch (missing: ClassNotFoundException) {
            fail("The app does not yet expose a testable MCP schema contract")
            throw missing
        }
        val obj = klass.getField("INSTANCE").get(null)
        return klass.getDeclaredMethod("schema", String::class.java).apply { isAccessible = true }
            .invoke(obj, name) as Map<String, Any>
    }
    @Test fun phoneActionDescribesItsRealRequiredActionAndParameters() {
        val schema = schema("phone_action")
        assertEquals("object", schema["type"])
        assertEquals(false, schema["additionalProperties"])
        assertEquals(listOf("action"), schema["required"])
        val props = schema["properties"] as Map<*, *>
        for (key in listOf("action", "package", "query", "snapshot", "ref", "text", "offset", "request_id", "confirmation", "_voice_request_id"))
            assertTrue("Missing action property: $key", props.containsKey(key))
    }
    @Test fun planAllowsVoiceOwnerAndRequiresPlan() {
        val schema = schema("phone_run_plan")
        assertEquals(listOf("plan"), schema["required"])
        val props = schema["properties"] as Map<*, *>
        assertTrue(props.containsKey("plan"))
        assertTrue(props.containsKey("_voice_request_id"))
    }
}
