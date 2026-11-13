package com.mist.connect

import android.app.*
import android.content.Intent
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Authenticated, loopback-only, read-only UI and user-selected media search bridge. */
class McpService : Service() {
    companion object { @Volatile var instance: McpService? = null; @Volatile var actionsPaused = false
        set(value) { field=value; if(value) ActionConfirmation.cancelAll() } }
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    fun boundMcpUrl(): String {
        check(running && instance === this) { "mcp_not_bound" }
        return McpEndpoint.url(checkNotNull(server))
    }
    @Volatile private var client: Socket? = null
    private var worker: Thread? = null
    private val nativeSttLock = Any()
    private var nativeSttRequest: AndroidSttRequest? = null

    private fun startNativeSttLifetime() = synchronized(nativeSttLock) {
        running = true; instance = this
    }
    private fun stopNativeSttLifetime() = synchronized(nativeSttLock) {
        // Linearize cancellation on the request's actual completion state BEFORE
        // publishing stopped. No native IO, caller callbacks or waits under either lock.
        nativeSttRequest?.cancel()
        running = false; instance = null
    }
    private fun nativeSttFailure(code: String) = JSONObject().put("success", false)
        .put("transcript", "").put("provider", "android-native").put("on_device", true).put("error", code)
        .put("stt_contract", 2).put("stt_cleanup_confirmed", true)
        .put("stt_reason", if (code == "stt_cancelled") "cancelled" else "failed")

    private fun transcribeNativeStt(data: ByteArray,
                                    options: AndroidSttInputOptions = AndroidSttInputOptions.LEGACY): JSONObject {
        val request = synchronized(nativeSttLock) {
            if (!running || instance !== this) return nativeSttFailure("stt_cancelled")
            if (nativeSttRequest != null) return nativeSttFailure("stt_busy")
            AndroidSttRequest(data.size).also { nativeSttRequest = it }
        }
        try {
            return if (options === AndroidSttInputOptions.LEGACY)
                AndroidNativeStt.transcribe(applicationContext, data, request)
            else AndroidNativeStt.transcribe(applicationContext, data, request, options)
        } finally {
            synchronized(nativeSttLock) {
                request.cancel() // no-op after seal; also retires pre-admission failures
                if (nativeSttRequest === request) nativeSttRequest = null
            }
        }
    }
    private val prefs by lazy { getSharedPreferences("lc_config", MODE_PRIVATE) }
    private lateinit var instanceId: String
    private val actions by lazy { PhoneActions(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onCreate() {
        super.onCreate()
        VoiceDiagnostics.attachLogFile(filesDir) // metadata readiness without opening the microphone
        if ((prefs.getString("mcp_token", "") ?: "").length < 32) { stopSelf(); return }
        instanceId = prefs.getString("phone_instance_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("phone_instance_id", it).commit()
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("phone_readonly", getString(R.string.mcp_notif_title), NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "phone_readonly")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.mcp_notif_title))
            .setContentText(getString(R.string.mcp_notif_text)).setOngoing(true).build())
        try { server = McpEndpoint.bind() }
        catch (_: Exception) { stopSelf(); return }
        startNativeSttLifetime(); actionsPaused = false; ActionConfirmation.watchLocks(this)
        worker = Thread {
            try {
                while (running) {
                    val accepted = server?.accept() ?: break
                    client = accepted
                    if (!running) { accepted.close(); break }
                    accepted.use { handle(it) }
                    client = null
                }
            } catch (_: Exception) { if (running) stopSelf() }
        }.also { it.start() }
    }
    override fun onDestroy() {
        stopNativeSttLifetime(); PairingProbe.cancel(); ActionConfirmation.stopWatching(this)
        runCatching { server?.close() }; runCatching { client?.close() }
        worker?.interrupt()
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) }
    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= 4096) {
            val b = input.read(); require(b >= 0)
            if (b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(b)
        }
        error("Header too long")
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
        fun reply(code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $code ${if (code == 200) "OK" else "Response"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        }
        try {
            val input = socket.getInputStream()
            val request = readLine(input)
            val nativeStt = request == "POST /stt/ko HTTP/1.1"
            if (!nativeStt && request != "POST /mcp HTTP/1.1") { reply(404, "{}"); return }
            val headers = mutableMapOf<String,String>()
            var total = 0
            while (true) {
                val line = readLine(input); if (line.isEmpty()) break
                total += line.length; require(total <= 16384)
                val colon = line.indexOf(':'); require(colon > 0)
                val key = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
                require(!headers.containsKey(key)); headers[key] = line.substring(colon+1).trim()
            }
            val token = prefs.getString("mcp_token", "") ?: ""
            val auth = headers["authorization"] ?: ""
            if (token.length < 32 || !MessageDigest.isEqual(("Bearer $token").toByteArray(), auth.toByteArray())) { reply(401,"{}"); return }
            if (headers.containsKey("origin") || headers.containsKey("transfer-encoding")) { reply(403,"{}"); return }
            val size = headers["content-length"]?.toIntOrNull() ?: 0
            if (size !in 1..(if (nativeStt) AndroidNativeStt.MAX_PCM else 65536)) { reply(413,"{}"); return }
            if (nativeStt && (size < 320 || size % 2 != 0 || headers["content-type"] != "application/octet-stream")) {
                reply(400, "{}"); return
            }
            val data = ByteArray(size); var offset = 0
            try {
                while (offset < size) { val n = input.read(data, offset, size-offset); require(n > 0); offset += n }
                if (nativeStt) {
                    val options = try { AndroidSttInputOptions.fromHeaders(headers) }
                    catch (_: IllegalArgumentException) {
                        reply(200, nativeSttFailure("stt_pacing_invalid").put("instance_id", instanceId).toString())
                        return
                    }
                    val result = transcribeNativeStt(data, options)
                    result.put("instance_id", instanceId)
                    reply(200, result.toString())
                    return
                }
            } finally { if (nativeStt) data.fill(0) }
            if (!running) { reply(503,"{}"); return }
            val req = JSONObject(String(data, Charsets.UTF_8))
            require(req.optString("jsonrpc") == "2.0")
            if (req.optString("method") == "notifications/initialized") { reply(202, ""); return }
            val result = dispatch(req)
            if (!running) { reply(503,"{}"); return }
            reply(200, JSONObject().put("jsonrpc","2.0").put("id",req.opt("id") ?: JSONObject.NULL).put("result",result).toString())
        } catch (_: Exception) { runCatching { reply(400,"{}") } }
    }
    private fun documentRevision(args: JSONObject): JSONObject {
        require(args.keys().asSequence().toSet() == setOf("voice_request_id","name","before","after","old_text","new_text","action_digest"))
        val d=HostDocumentRevision(args.getString("voice_request_id"),args.getString("name"),
            args.getString("before"),args.getString("after"),args.getString("old_text"),
            args.getString("new_text"),args.getString("action_digest"))
        fun valid(): Boolean = running && instance === this && !actionsPaused &&
            getSharedPreferences("handsfree",0).getBoolean("control_consent",false) &&
            !getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId == d.request
        check(valid()) { "voice_owner_required" }
        return ActionConfirmation.request(d.prompt(),d.request,HostDocumentRevision.phrase(),d.digest,
            { valid() }, { JSONObject().put("dispatched",true).put("permission_only",true)
                .put("voice_request_id",d.request).put("authorized_digest",d.digest) })
    }
    private fun codeExecution(args: JSONObject): JSONObject {
        require(args.keys().asSequence().toSet() == setOf("voice_request_id","name","source","source_sha256","action_digest"))
        val d=HostCodeExecution(args.getString("voice_request_id"),args.getString("name"),
            args.getString("source"),args.getString("source_sha256"),args.getString("action_digest"))
        fun valid(): Boolean = running && instance === this && !actionsPaused &&
            getSharedPreferences("handsfree",0).getBoolean("control_consent",false) &&
            !getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            VoiceRequestService.handsfreeActive && VoiceRequestService.activeRequestId == d.request
        check(valid()) { "voice_owner_required" }
        return ActionConfirmation.request(d.prompt(),d.request,HostCodeExecution.phrase(),d.digest,
            { valid() }, { JSONObject().put("dispatched",true).put("permission_only",true)
                .put("voice_request_id",d.request).put("authorized_digest",d.digest) })
    }
    private fun status(): JSONObject {
        val unlocked = !getSystemService(KeyguardManager::class.java).isDeviceLocked
        return JSONObject().put("instance_id",instanceId).put("ready",running)
            .put("unlocked",unlocked).put("accessibility",LCAccessibilityService.instance != null).put("action_confirmation_version",1).put("voice_confirmation_version",1).put("host_document_revision_version",1).put("host_code_execution_version",1).put("plan_executor_version",1).put("voice_diagnostics",VoiceDiagnostics.status())
            .put("voice_request_id",VoiceRequestService.activeRequestId).put("handsfree_active",VoiceRequestService.handsfreeActive)
            .put("control_allowed",!actionsPaused && getSharedPreferences("handsfree",0).getBoolean("control_consent",false))
            .put("android_stt_version", 1).put("android_stt_mode", "on_device_ko_pcm_only")
    }
    private fun dispatch(req: JSONObject): JSONObject {
        return when (req.optString("method")) {
            "initialize" -> JSONObject().put("protocolVersion","2025-03-26")
                .put("capabilities",JSONObject().put("tools",JSONObject().put("listChanged",false)))
                .put("serverInfo",JSONObject().put("name","HermesPhoneReadOnly").put("version","0.1.0"))
            "tools/list" -> {
                val tools = JSONArray()
                for (name in listOf("phone_backend_status","get_ui_tree","phone_observe_screen","phone_action","phone_run_plan")) {
                    val schema = JSONObject(McpToolSchemas.schema(name))
                    val description = if (name == "phone_run_plan")
                        "Run a deterministic multi-step UI plan inside the app (no per-step model round trip). " +
                        "Every step is still judged by PhoneActionPolicy; confirmation-required actions stop the plan. " +
                        "Steps: launch(package), await(role/id/id_suffix/text,timeout_ms), focus, input(text), submit, " +
                        "select(index), click, back, home, assert. Returns per-step timings and the failed step."
                    else "Read-only phone state: $name"
                    tools.put(JSONObject().put("name",name).put("description",description).put("inputSchema",schema))
                }
                JSONObject().put("tools",tools)
            }
            "tools/call" -> {
                val params = req.getJSONObject("params"); val name = params.getString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                var failed = false
                val value = try {
                    when (name) {
                        "phone_backend_status" -> { require(args.length()==0); status() }
                        "phone_pairing_probe" -> PairingProbe.check(this, args) { running && instance === this }
                        "phone_pairing_endpoint_probe" -> PairingProbe.checkEndpoint(this, args, boundMcpUrl()) { running && instance === this }
                        "phone_observe_screen" -> { require(args.length()==0); actions.observe() }
                        "phone_action" -> actions.execute(args)
                        "phone_run_plan" -> {
                            require(args.keys().asSequence().toSet() == setOf("plan","_voice_request_id") ||
                                    args.keys().asSequence().toSet() == setOf("plan")) { "invalid_arguments" }
                            val voice = args.optString("_voice_request_id")
                            if (VoiceRequestService.activeRequestId.isNotEmpty() || voice.isNotEmpty())
                                require(voice.isNotEmpty() && VoiceRequestService.handsfreeActive &&
                                        VoiceRequestService.activeRequestId == voice) { "voice_owner_required" }
                            val budget = args.getJSONObject("plan").optInt("budget_ms", 8000).coerceIn(500, 15000)
                            PlanExecutor(actions).run(args.getJSONObject("plan"), voice, budget.toLong())
                        }
                        "host_document_revision" -> documentRevision(args)
                        "host_code_execution" -> codeExecution(args)
                        "get_ui_tree" -> {
                            val s = status(); require(s.getBoolean("ready") && s.getBoolean("unlocked") && s.getBoolean("accessibility"))
                            require(args.keys().asSequence().all { it in setOf("max_depth","include_bounds") })
                            val depth = args.optInt("max_depth",8); require(depth in 1..8)
                            val tree = LCAccessibilityService.instance!!.dumpUiTree(depth,args.optBoolean("include_bounds",true))
                            require(status().getBoolean("unlocked") && running)
                            tree
                        }
                        else -> error("Unsupported tool")
                    }
                } catch (error: Exception) { failed = true; JSONObject().put("error","Tool denied or unavailable").put("code",VoiceConfirmationPolicy.errorCode(error.message))
                    .apply { VoiceConfirmationPolicy.missingField(error.message).takeIf { it.isNotEmpty() }?.let { put("missing",it) } } }
                JSONObject().put("isError",failed).put("content",JSONArray().put(JSONObject().put("type","text").put("text",value.toString())))
            }
            else -> error("Unsupported method")
        }
    }
}
