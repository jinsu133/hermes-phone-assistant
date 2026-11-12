package com.mist.connect

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mist.connect.ui.theme.MistConnectTheme

/** Integration must register this activity as android:exported="false". */
class PermissionOverviewActivity : ComponentActivity() {
    private enum class Step { MICROPHONE, NOTIFICATIONS, ACCESSIBILITY, OVERLAY, DONE }

    private var current by mutableStateOf(unobserved())
    private var message by mutableStateOf(R.string.permission_overview_initial)
    private var nextStep by mutableStateOf(Step.DONE)
    private var pending by mutableStateOf<Step?>(null)
    private lateinit var microphoneRequest: ActivityResultLauncher<String>
    private lateinit var notificationRequest: ActivityResultLauncher<String>
    private lateinit var accessibilitySettings: ActivityResultLauncher<Intent>
    private lateinit var overlaySettings: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register unconditionally in stable order, before STARTED, including after restoration.
        microphoneRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            onPermissionReturned(Step.MICROPHONE)
        }
        notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            onPermissionReturned(Step.NOTIFICATIONS)
        }
        accessibilitySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onSettingsReturned(Step.ACCESSIBILITY)
        }
        overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onSettingsReturned(Step.OVERLAY)
        }
        // An unfinished run is cancelled, never replayed. A late result only refreshes status.
        if (savedInstanceState?.getBoolean(KEY_INCOMPLETE) == true) {
            message = R.string.permission_overview_interrupted
        }
        refresh()
        setContent {
            MistConnectTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Overview(current, message, pending != null || nextStep != Step.DONE,
                        ::startGuided, {
                            refresh()
                            message = R.string.permission_overview_refreshed
                        })
                }
            }
        }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_GUIDED, false)) {
            startGuided()
        }
    }

    override fun onResume() {
        super.onResume()
        // Resume is not a result: it must not advance or request another permission.
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_INCOMPLETE, pending != null || nextStep != Step.DONE)
        super.onSaveInstanceState(outState)
    }

    private fun startGuided() {
        if (pending != null || nextStep != Step.DONE) return
        refresh()
        message = R.string.permission_overview_in_progress
        nextStep = Step.MICROPHONE
        advance()
    }

    private fun onPermissionReturned(step: Step) {
        refresh() // The returned Boolean is not evidence; read the system state.
        if (pending != step) return
        pending = null
        message = when (step) {
            Step.MICROPHONE -> if (current.microphone == PermissionSignal.GRANTED)
                R.string.permission_overview_microphone_returned else R.string.permission_overview_permission_denied
            Step.NOTIFICATIONS -> if (current.notificationRuntime == PermissionSignal.GRANTED)
                R.string.permission_overview_notifications_returned else R.string.permission_overview_permission_denied
            else -> R.string.permission_overview_refreshed
        }
        advance()
    }

    private fun onSettingsReturned(step: Step) {
        refresh() // Settings resultCode does not grant access.
        if (pending != step) return
        pending = null
        message = R.string.permission_overview_settings_returned
        advance()
    }

    private fun advance() {
        if (pending != null) return
        while (nextStep != Step.DONE) {
            val step = nextStep
            // Move forward BEFORE launching: each step is visited at most once per tap.
            nextStep = when (step) {
                Step.MICROPHONE -> Step.NOTIFICATIONS
                Step.NOTIFICATIONS -> Step.ACCESSIBILITY
                Step.ACCESSIBILITY -> Step.OVERLAY
                Step.OVERLAY, Step.DONE -> Step.DONE
            }
            refresh()
            when (step) {
                Step.MICROPHONE -> if (current.microphone != PermissionSignal.GRANTED &&
                    launchPermission(step, microphoneRequest, Manifest.permission.RECORD_AUDIO)) return
                Step.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 &&
                    current.notificationRuntime != PermissionSignal.GRANTED &&
                    launchPermission(step, notificationRequest, Manifest.permission.POST_NOTIFICATIONS)) return
                Step.ACCESSIBILITY -> if (current.accessibilityPermission != PermissionSignal.GRANTED &&
                    launchSettings(step, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return
                Step.OVERLAY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    current.overlayPermission != PermissionSignal.GRANTED &&
                    launchSettings(step, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))) return
                Step.DONE -> Unit
            }
        }
        // Never show an all-clear: permission and instance checks do not test operation.
        if (message == R.string.permission_overview_in_progress) {
            message = R.string.permission_overview_finished
        }
    }

    private fun launchPermission(step: Step, launcher: ActivityResultLauncher<String>, permission: String): Boolean {
        pending = step
        return try {
            launcher.launch(permission)
            true
        } catch (_: Exception) {
            pending = null
            message = R.string.permission_overview_request_failed
            false
        }
    }

    private fun launchSettings(step: Step, intent: Intent): Boolean {
        // Both resolution and launch can fail on restricted devices.
        val handler = try { intent.resolveActivity(packageManager) } catch (_: Exception) {
            message = R.string.permission_overview_request_failed
            return false
        }
        if (handler == null) {
            message = R.string.permission_overview_settings_unavailable
            return false
        }
        pending = step
        return try {
            (if (step == Step.ACCESSIBILITY) accessibilitySettings else overlaySettings).launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            pending = null
            message = R.string.permission_overview_settings_unavailable
            false
        } catch (_: Exception) {
            pending = null
            message = R.string.permission_overview_request_failed
            false
        }
    }

    private fun refresh() {
        val accessibilityAllowed = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.split(':')
                    ?.mapNotNull(ComponentName::unflattenFromString)
                    ?.any { it == ComponentName(this, LCAccessibilityService::class.java) } == true
        }.getOrNull()
        current = PermissionAssessment.from(PermissionSnapshot(
            microphoneGranted = runCatching {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrNull(),
            notificationRuntimeRequired = Build.VERSION.SDK_INT >= 33,
            notificationGranted = if (Build.VERSION.SDK_INT >= 33) runCatching {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrNull() else null,
            notificationsEnabled = if (Build.VERSION.SDK_INT >= 24) runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.areNotificationsEnabled()
            }.getOrNull() else null,
            accessibilityAllowed = accessibilityAllowed,
            accessibilityInstancePresent = runCatching { LCAccessibilityService.instance != null }.getOrNull(),
            overlayAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                runCatching { Settings.canDrawOverlays(this) }.getOrNull() else null,
            overlayOptIn = runCatching { OptionalStatusOverlay.enabled(this) }.getOrNull(),
            // Live service state and explicit app consent are separate from OS permissions.
            handsfreeOptIn = runCatching { VoiceRequestService.handsfreeActive }.getOrNull(),
            controlConsent = runCatching { getSharedPreferences("handsfree", MODE_PRIVATE)
                .getBoolean("control_consent", false) }.getOrNull(),
            mcpInstancePresent = runCatching { McpService.instance != null }.getOrNull()
        ))
    }

    @Composable
    private fun Overview(
        assessment: PermissionAssessment, message: Int, busy: Boolean,
        start: () -> Unit, refresh: () -> Unit
    ) {
        var showDetails by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.permission_overview_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.permission_overview_intro))
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = start, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permission_overview_start))
            }
            OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permission_overview_refresh))
            }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(if (showDetails) R.string.permission_overview_hide_details else R.string.permission_overview_show_details))
            }
            SignalRow(R.string.permission_overview_microphone, R.string.permission_overview_microphone_why, assessment.microphone, showDetails)
            SignalRow(R.string.permission_overview_notification_runtime, R.string.permission_overview_notifications_why, assessment.notificationRuntime, showDetails)
            SignalRow(R.string.permission_overview_notification_delivery, R.string.permission_overview_notifications_delivery_why, assessment.notificationDelivery, showDetails)
            SignalRow(R.string.permission_overview_accessibility_os, R.string.permission_overview_accessibility_why, assessment.accessibilityPermission, showDetails)
            SignalRow(R.string.permission_overview_accessibility_instance, R.string.permission_overview_accessibility_instance_why, assessment.accessibilityConnection, showDetails)
            SignalRow(R.string.permission_overview_overlay_os, R.string.permission_overview_overlay_why, assessment.overlayPermission, showDetails)
            SignalRow(R.string.permission_overview_overlay_optin, R.string.permission_overview_overlay_optin_why, assessment.overlayOptIn, showDetails)
            SignalRow(R.string.permission_overview_handsfree_optin, R.string.permission_overview_handsfree_why, assessment.handsfreeOptIn, showDetails)
            SignalRow(R.string.permission_overview_control_consent, R.string.permission_overview_control_why, assessment.controlConsent, showDetails)
            SignalRow(R.string.permission_overview_mcp_instance, R.string.permission_overview_mcp_why, assessment.mcpConnection, showDetails)
            Text(stringResource(R.string.permission_overview_limit), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun SignalRow(label: Int, explanation: Int, signal: PermissionSignal, showDetails: Boolean) {
        val state = when (signal) {
            PermissionSignal.NOT_OBSERVED -> R.string.permission_overview_not_observed
            PermissionSignal.NOT_APPLICABLE -> R.string.permission_overview_not_applicable
            PermissionSignal.DENIED -> R.string.permission_overview_denied
            PermissionSignal.GRANTED -> R.string.permission_overview_granted
            PermissionSignal.OFF -> R.string.permission_overview_off
            PermissionSignal.ON -> R.string.permission_overview_on
            PermissionSignal.ALLOWED_DISCONNECTED -> R.string.permission_overview_allowed_disconnected
            PermissionSignal.INSTANCE_OBSERVED -> R.string.permission_overview_instance_observed
            PermissionSignal.STOPPED -> R.string.permission_overview_stopped
        }
        Text(stringResource(R.string.permission_overview_row, stringResource(label), stringResource(state)),
            style = MaterialTheme.typography.titleSmall)
        if (showDetails) Text(stringResource(explanation), style = MaterialTheme.typography.bodySmall)
    }

    companion object {
        const val EXTRA_START_GUIDED = "start_guided_permissions"
        private const val KEY_INCOMPLETE = "permission_overview_incomplete"
        private fun unobserved() = PermissionAssessment.from(PermissionSnapshot(
            null, true, null, null, null, null, null, null, null, null, null
        ))
    }
}
