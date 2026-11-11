package com.mist.connect

/** Exact argument shapes advertised over MCP; execution policy remains in PhoneActions. */
object McpToolSchemas {
    private fun string() = mapOf<String, Any>("type" to "string")
    fun schema(name: String): Map<String, Any> {
        val props: Map<String, Any> = when (name) {
            "get_ui_tree" -> mapOf(
                "max_depth" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 8),
                "include_bounds" to mapOf("type" to "boolean")
            )
            "phone_action" -> mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("list_apps", "open_app", "home", "back", "recents", "notifications", "click", "read_node", "input_text", "submit_search", "scroll_forward", "scroll_backward", "approval_status", "cancel_approval")),
                "package" to string(), "query" to string(), "snapshot" to string(), "ref" to string(),
                "text" to string(), "offset" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 64000),
                "request_id" to string(), "_voice_request_id" to string(),
                "confirmation" to mapOf("type" to "object", "additionalProperties" to false,
                    "properties" to mapOf("kind" to mapOf("type" to "string", "enum" to listOf("general", "call", "message", "payment")),
                        "recipient" to string(), "content" to string(), "merchant" to string(),
                        "amount" to string(), "method" to string()))
            )
            "phone_run_plan" -> mapOf("plan" to mapOf("type" to "object"), "_voice_request_id" to string())
            "phone_backend_status", "phone_observe_screen" -> emptyMap()
            else -> throw IllegalArgumentException("unknown_mcp_tool")
        }
        val required = when (name) {
            "phone_action" -> listOf("action")
            "phone_run_plan" -> listOf("plan")
            else -> emptyList()
        }
        return mapOf("type" to "object", "properties" to props,
            "required" to required, "additionalProperties" to false)
    }
}
