package com.mist.connect

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.*
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("lc_config", MODE_PRIVATE) }
    private var uiState by mutableStateOf(CompanionUiRepository.snapshots.value)
    private var diagnosticText by mutableStateOf("")
    private val commands = HomeCommandRouter()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Credentials remain in private preferences; this screen never renders their values.
        if ((prefs.getString("mcp_token", "") ?: "").length < 32) {
            val bytes = ByteArray(32); SecureRandom().nextBytes(bytes)
            check(prefs.edit().putString("mcp_token", Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)).commit())
        }
        if (!VoiceBridgeProtocol.validToken(prefs.getString("voice_token", ""))) {
            val bytes = ByteArray(32); SecureRandom().nextBytes(bytes)
            check(prefs.edit().putString("voice_token",
                Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)).commit())
        }
        if (!prefs.contains("phone_instance_id")) {
            check(prefs.edit().putString("phone_instance_id", UUID.randomUUID().toString()).commit())
        }

        val actions = mutableListOf<HomeAction>()
        fun command(id: HomeCommand, title: Int, section: HomeSection, effect: () -> Unit) {
            actions.add(HomeAction(id, getString(title), section))
            commands.bind(id, effect)
        }
        command(HomeCommand.PERMISSIONS_OVERVIEW, R.string.permission_overview_integration, HomeSection.PRIMARY) {
            startActivity(Intent(this, PermissionOverviewActivity::class.java)
                .putExtra(PermissionOverviewActivity.EXTRA_START_GUIDED, true))
        }
        command(HomeCommand.LOCK_SETTINGS, R.string.action_lock_settings, HomeSection.EVERYDAY) { startActivity(Intent(this, LockSettingsActivity::class.java)) }
        command(HomeCommand.CONTROL_CONSENT, R.string.action_control_consent, HomeSection.EVERYDAY) {
            AlertDialog.Builder(this).setTitle(R.string.consent_title).setMessage(R.string.consent_message)
                .setNegativeButton(R.string.consent_revoke) { _, _ -> getSharedPreferences("handsfree", MODE_PRIVATE).edit().putBoolean("control_consent", false).apply(); stopService(Intent(this, McpService::class.java)) }
                .setPositiveButton(R.string.consent_allow) { _, _ -> getSharedPreferences("handsfree", MODE_PRIVATE).edit().putBoolean("control_consent", true).apply(); startForegroundService(Intent(this, McpService::class.java)) }.show()
        }
        if (BuildConfig.DEBUG) command(HomeCommand.UNLOCK_PROBE, R.string.action_unlock_probe, HomeSection.DIAGNOSTICS) {
            val app = applicationContext
            getSharedPreferences("unlock_probe", MODE_PRIVATE).edit().putString("result","scheduled").apply()
            toast(getString(R.string.toast_unlock_probe))
            Thread({
                Thread.sleep(5000)
                app.getSharedPreferences("unlock_probe", MODE_PRIVATE).edit().putString("result","waiting").apply()
                val resumed = UnlockCoordinator.await(app) { true }
                app.getSharedPreferences("unlock_probe", MODE_PRIVATE).edit().putString("result",if (resumed) "resumed" else "cancelled_or_expired").commit()
            }, "unlock-path-probe").start()
        }
        command(HomeCommand.WAKE_DIAGNOSTIC, R.string.action_wake_diagnostic, HomeSection.DIAGNOSTICS) { startActivity(Intent(this, WakeDiagnosticActivity::class.java)) }
        command(HomeCommand.LISTEN_ON, R.string.action_listen_on, HomeSection.PRIMARY) { startActivity(Intent(this, VoiceStartActivity::class.java)) }
        command(HomeCommand.LISTEN_OFF, R.string.action_listen_off, HomeSection.PRIMARY) { startService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.CANCEL_REQUEST)) }
        command(HomeCommand.PIN_WIDGET, R.string.action_pin_widget, HomeSection.EVERYDAY) {
            val manager = android.appwidget.AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(this, VoiceWidget::class.java), null, null)
            else toast(getString(R.string.toast_pin_widget))
        }
        command(HomeCommand.SETUP, R.string.action_setup, HomeSection.EVERYDAY) { startActivity(Intent(this, SetupActivity::class.java)) }
        command(HomeCommand.EXPORT_UNIFIED, R.string.action_export_unified, HomeSection.CONNECTION) { exportUnifiedPairing() }
        command(HomeCommand.EXPORT_VOICE, R.string.action_export_voice, HomeSection.CONNECTION) { exportVoicePairing() }
        command(HomeCommand.START_REQUEST, R.string.action_start_request, HomeSection.MANUAL) { startVoiceRequest() }
        command(HomeCommand.SUBMIT_REQUEST, R.string.action_submit_request, HomeSection.MANUAL) {
            startService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.SUBMIT_REQUEST))
            uiHandler.postDelayed({ refresh() }, 300)
        }
        command(HomeCommand.CANCEL_REQUEST, R.string.action_cancel_request, HomeSection.MANUAL) {
            startService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.CANCEL_REQUEST))
            stopService(Intent(this, VoiceForegroundService::class.java))
            uiHandler.postDelayed({ refresh() }, 300)
        }
        command(HomeCommand.RECENT_REPLY, R.string.action_recent_reply, HomeSection.EVERYDAY) {
            val text = VoiceRequestService.lastReplyText
            AlertDialog.Builder(this).setTitle(R.string.recent_reply_title)
                .setMessage(if (text.isBlank()) getString(R.string.recent_reply_empty) else text)
                .setPositiveButton(R.string.close, null).show()
        }
        command(HomeCommand.WAKE_PROBE, R.string.action_wake_probe, HomeSection.DIAGNOSTICS) { startProbe(true) }
        command(HomeCommand.MIC_PROBE, R.string.action_mic_probe, HomeSection.DIAGNOSTICS) { startProbe(false) }
        command(HomeCommand.STOP_PROBE, R.string.action_stop_probe, HomeSection.DIAGNOSTICS) {
            stopService(Intent(this, VoiceForegroundService::class.java)); refresh()
        }
        command(HomeCommand.ACCESSIBILITY_SETTINGS, R.string.action_accessibility_settings, HomeSection.EVERYDAY) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        command(HomeCommand.EXPORT_UI, R.string.action_export_ui, HomeSection.CONNECTION) {
            AlertDialog.Builder(this).setTitle(R.string.export_ui_title).setMessage(R.string.export_ui_message)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.choose_location) { _, _ ->
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "hermes-phone-connection.json")
                    }, 100)
                }.show()
        }
        command(HomeCommand.START_MCP, R.string.action_start_mcp, HomeSection.CONNECTION) {
            if (LCAccessibilityService.instance == null) toast(getString(R.string.toast_accessibility_required))
            else {
                startForegroundService(Intent(this, McpService::class.java))
                uiHandler.postDelayed({ refresh() }, 700)
            }
        }
        command(HomeCommand.ROTATE_UI_KEY, R.string.action_rotate_ui_key, HomeSection.ADVANCED) {
            AlertDialog.Builder(this).setMessage(R.string.rotate_ui_message)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.rotate_key) { _, _ ->
                    stopService(Intent(this, McpService::class.java))
                    val bytes = ByteArray(32); SecureRandom().nextBytes(bytes)
                    prefs.edit().putString("mcp_token", Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)).commit()
                    toast(getString(R.string.toast_ui_rotated)); refresh()
                }.show()
        }
        command(HomeCommand.ROTATE_VOICE_KEY, R.string.action_rotate_voice_key, HomeSection.ADVANCED) {
            AlertDialog.Builder(this).setMessage(R.string.rotate_voice_message)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.rotate_key) { _, _ -> rotateVoiceKey() }.show()
        }
        command(HomeCommand.UPDATE_ENDPOINT, R.string.action_update_endpoint, HomeSection.CONNECTION) {
            Thread({
                val outcome = runCatching { PairingEndpointUpdate.run(this) }
                runOnUiThread { toast(outcome.getOrNull() ?: getString(R.string.toast_endpoint_unknown)) }
            }, "pairing-endpoint-update").start()
        }
        command(HomeCommand.STOP_EVERYTHING, R.string.action_stop_everything, HomeSection.PRIMARY) { stopEverything() }
        command(HomeCommand.OVERLAY_SETTINGS, R.string.action_overlay_settings, HomeSection.EVERYDAY) { OverlaySettingsDialog.show(this) }

        // Composition only renders values; command effects are registered but never invoked here.
        val immutableActions = actions.toList()
        setContent { HomeScreen(uiState, diagnosticText, immutableActions) { command ->
            if (command == HomeCommand.LISTEN_OFF || command == HomeCommand.CANCEL_REQUEST ||
                command == HomeCommand.STOP_EVERYTHING) CompanionUiRepository.offRequested()
            commands.dispatch(command)
        } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val observation = CompanionUiRepository.observe(this@MainActivity)
                try {
                    CompanionUiRepository.snapshots.collect {
                        uiState = it
                        refreshDiagnostics()
                    }
                } finally { observation.close() }
            }
        }
        VoiceWidget.update(this)
        refresh()
    }

    private fun exportUnifiedPairing() {
        AlertDialog.Builder(this).setTitle(R.string.export_unified_title).setMessage(R.string.export_unified_message)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.choose_location) { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "hermes-phone-pairing.json")
                }, 102)
            }.show()
    }

    private fun unifiedPairing(): JSONObject {
        var pairingId = prefs.getString("unified_pairing_id", null)
        if (pairingId == null) {
            pairingId = UUID.randomUUID().toString()
            check(prefs.edit().putString("unified_pairing_id", pairingId).commit())
        }
        return JSONObject().put("version", 2).put("kind", "hermes-phone")
            .put("pairing_id", pairingId).put("revision", prefs.getLong("unified_pairing_revision", 1L))
            .put("device_instance_id", prefs.getString("phone_instance_id", ""))
            .put("ui", JSONObject().put("url", checkNotNull(McpService.instance) { "mcp_not_bound" }.boundMcpUrl())
                .put("token", prefs.getString("mcp_token", "")).put("protocol_version", 1))
            .put("voice", JSONObject().put("url", "http://127.0.0.1:5001/")
                .put("token", prefs.getString("voice_token", "")).put("protocol_version", 1))
    }

    private fun exportVoicePairing() {
        AlertDialog.Builder(this).setTitle(R.string.export_voice_title).setMessage(R.string.export_voice_message)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.choose_location) { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "hermes-voicelink-connection.json")
                }, 101)
            }.show()
    }

    private fun startVoiceRequest() {
        if (android.os.Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 203)
            toast(getString(R.string.toast_notifications_required))
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 204)
            toast(getString(R.string.toast_microphone_required))
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.request_title).setMessage(R.string.request_message)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.start_recording) { _, _ ->
                try {
                    startForegroundService(Intent(this, VoiceRequestService::class.java)
                        .setAction(VoiceRequestService.START_REQUEST))
                } catch (_: RuntimeException) { toast(getString(R.string.toast_request_failed)) }
            }.show()
    }

    private fun startProbe(wake: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 202)
            toast(getString(R.string.toast_notifications_required))
        } else if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 201)
            toast(getString(R.string.toast_microphone_required))
        } else {
            AlertDialog.Builder(this).setTitle(if (wake) R.string.probe_wake_title else R.string.probe_mic_title)
                .setMessage(getString(R.string.probe_message, WakePhraseGate.PHRASE))
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.probe_start) { _, _ ->
                    try {
                        startForegroundService(Intent(this, VoiceForegroundService::class.java)
                            .setAction(if (wake) "START_WAKE_PROBE" else "START_PROBE"))
                    } catch (_: RuntimeException) { toast(getString(R.string.toast_service_rejected)) }
                }.show()
        }
    }

    private fun rotateVoiceKey() {
        val previous = prefs.getString("voice_token", "") ?: ""
        startService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.CANCEL_REQUEST))
        if (VoiceBridgeProtocol.validToken(previous)) {
            Thread({ VoiceBridgeClient.stopBridge(previous) { } }, "voice-bridge-revoke").start()
        }
        val bytes = ByteArray(32); SecureRandom().nextBytes(bytes)
        val fresh = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        check(prefs.edit().putString("voice_token", fresh).commit())
        toast(getString(R.string.toast_voice_rotated))
        refresh()
    }

    private fun stopEverything() {
        val token = prefs.getString("voice_token", "") ?: ""
        stopService(Intent(this, VoiceForegroundService::class.java))
        startService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.CANCEL_REQUEST))
        stopService(Intent(this, McpService::class.java))
        if (VoiceBridgeProtocol.validToken(token)) {
            Thread({ VoiceBridgeClient.stopBridge(token) { } }, "voice-bridge-off").start()
        }
        toast(getString(R.string.toast_stop_requested))
        uiHandler.postDelayed({ refresh() }, 300)
    }

    override fun onResume() {
        super.onResume()
        VoiceForegroundService.transcript.setVisible(true)
    }
    override fun onPause() {
        VoiceForegroundService.transcript.setVisible(false)
        refreshDiagnostics()
        super.onPause()
    }
    private fun refresh() {
        uiState = CompanionUiRepository.read(this)
        refreshDiagnostics()
    }
    private fun refreshDiagnostics() {
        val preview = VoiceForegroundService.transcript.snapshot().ifEmpty { getString(R.string.diagnostics_no_transcript) }
        diagnosticText = getString(R.string.diagnostics_runtime,
            if (McpService.instance != null) getString(R.string.on) else getString(R.string.off),
            if (LCAccessibilityService.instance != null) getString(R.string.connected) else getString(R.string.not_allowed),
            VoiceRequestService.status, VoiceForegroundService.status, preview,
            getSharedPreferences("voice_probe", MODE_PRIVATE).getString("result", getString(R.string.none)) ?: getString(R.string.none))
    }
    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    @Deprecated("Legacy bounded file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode != 100 && requestCode != 101 && requestCode != 102) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val json = if (requestCode == 102) unifiedPairing() else if (requestCode == 100) {
                JSONObject().put("version", 1).put("url", checkNotNull(McpService.instance) { "mcp_not_bound" }.boundMcpUrl())
                    .put("instance_id", prefs.getString("phone_instance_id", ""))
                    .put("token", prefs.getString("mcp_token", ""))
            } else {
                JSONObject().put("version", 2).put("kind", "hermes-voicelink")
                    .put("voice_url", "http://127.0.0.1:5001/")
                    .put("instance_id", prefs.getString("phone_instance_id", ""))
                    .put("voice_token", prefs.getString("voice_token", ""))
            }
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
            stream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            toast(getString(if (requestCode == 100) R.string.toast_ui_exported else R.string.toast_voice_exported))
        } catch (_: Exception) { toast(getString(R.string.toast_export_failed)) }
    }
}
