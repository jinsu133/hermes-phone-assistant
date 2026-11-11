package com.mist.connect

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mist.connect.ui.theme.MistConnectTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.Locale

/** Read-only release setup diagnostics; no package installs, shell, keys or recording. */
class SetupActivity : ComponentActivity() {
    private var uiState by mutableStateOf(CompanionUiRepository.snapshots.value)
    private val phase: SetupReadiness.Phase get() = uiState.readiness
    private var readinessQuery: Long? = null
    private var learningSummary by mutableStateOf("")
    private var worker: Thread? = null
    private var requestEpoch = 0L
    private var speechSpeed by mutableStateOf(SpeechPlaybackPolicy.DEFAULT_SPEED)
    private var pickerSnapshot by mutableStateOf<ModelPickerSnapshot?>(null)
    private var pickerSelection by mutableStateOf<ModelPickerSelection?>(null)
    private var pickerLoading by mutableStateOf(false)
    private var pickerMessage by mutableStateOf("")
    private var pickerWorker: Thread? = null
    private var pickerEpoch = 0L
    private val pickerClient = ModelPickerClient()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        learningSummary = getString(R.string.setup_learning_unchecked)
        pickerMessage = getString(R.string.setup_picker_intro)
        speechSpeed = try { SpeechPlaybackPolicy.storedSpeed(getSharedPreferences("handsfree", MODE_PRIVATE)
            .getFloat(SpeechPlaybackPolicy.PREFERENCE, SpeechPlaybackPolicy.DEFAULT_SPEED)) }
        catch (_: ClassCastException) { SpeechPlaybackPolicy.DEFAULT_SPEED }
        uiState = CompanionUiRepository.read(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val observation = CompanionUiRepository.observe(this@SetupActivity)
                try { CompanionUiRepository.snapshots.collect { uiState = it } }
                finally { observation.close() }
            }
        }
        setContent {
            MistConnectTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.setup_heading), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.setup_test_note))
                        CompanionStatusCard(uiState)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setup_connection), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(when (phase) {
                                    SetupReadiness.Phase.UNCHECKED -> R.string.setup_unchecked
                                    SetupReadiness.Phase.CHECKING -> R.string.setup_checking
                                    SetupReadiness.Phase.UNPAIRED -> R.string.setup_unpaired
                                    SetupReadiness.Phase.UNREACHABLE -> R.string.setup_unreachable
                                    SetupReadiness.Phase.HOST_UNSUPPORTED -> R.string.setup_unsupported
                                    SetupReadiness.Phase.AVAILABLE -> R.string.setup_available
                                }))
                                Text(stringResource(R.string.setup_contract_note))
                                Text(stringResource(R.string.setup_cancel_note))
                                Button(onClick = { refreshHost() }, enabled = phase != SetupReadiness.Phase.CHECKING,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_check_host)) }
                            }
                        }
                        ModelPickerCard(pickerSnapshot, pickerSelection, pickerLoading, pickerMessage,
                            onRefresh = { refreshModels() }, onSelection = { pickerSelection = it })
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setup_learning), style = MaterialTheme.typography.titleMedium)
                                Text(learningSummary)
                                Text(stringResource(R.string.setup_learning_policy))
                                Text(stringResource(R.string.setup_learning_privacy))
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setup_speed), style = MaterialTheme.typography.titleMedium)
                                // The number follows the Android app locale; the stored float is unchanged.
                                val locale = resources.configuration.locales[0]
                                Text(stringResource(R.string.setup_speed_value, String.format(locale, "%.1f", speechSpeed)))
                                Slider(value = speechSpeed, onValueChange = { speechSpeed = it },
                                    valueRange = SpeechPlaybackPolicy.MIN_SPEED..SpeechPlaybackPolicy.MAX_SPEED,
                                    steps = 11, onValueChangeFinished = {
                                        getSharedPreferences("handsfree", MODE_PRIVATE).edit()
                                            .putFloat(SpeechPlaybackPolicy.PREFERENCE, SpeechPlaybackPolicy.requireSpeed(speechSpeed)).apply()
                                    })
                                Text(stringResource(R.string.setup_speed_note))
                            }
                        }
                        Text(stringResource(R.string.setup_install), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.setup_install_note))
                        Button(onClick = { openTermux() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_open_termux)) }
                        Text(stringResource(R.string.setup_mcp_note))
                        Text(stringResource(R.string.setup_file_note))
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_back)) }
                    }
                }
            }
        }
    }

    private fun refreshHost() {
        if (worker?.isAlive == true) return
        val query = CompanionUiRepository.beginReadiness(this)
        readinessQuery = query
        val token = getSharedPreferences("lc_config", MODE_PRIVATE).getString("voice_token", "") ?: ""
        if (!VoiceBridgeProtocol.validToken(token)) {
            CompanionUiRepository.completeReadiness(this, query, SetupReadiness.Phase.UNPAIRED)
            readinessQuery = null
            return
        }
        val epoch = ++requestEpoch
        worker = Thread({
            val result = try {
                val value = VoiceBridgeClient.capabilities(token)
                SetupReadiness.classify(true, true, VoiceBridgeClient.permitsVoiceExecution(value))
            } catch (_: Exception) { SetupReadiness.Phase.UNREACHABLE }
            val learned = if (result == SetupReadiness.Phase.UNREACHABLE) getString(R.string.setup_learning_failed)
            else try {
                val data = VoiceBridgeClient.learningStatus(token)
                getString(R.string.setup_learning_counts, data.getInt("routes"), data.getInt("repeatedly_verified"), data.getInt("paused")) +
                    "\n" + getString(if (data.opt("enabled") == true) R.string.setup_learning_on else R.string.setup_learning_off)
            } catch (_: Exception) { getString(R.string.setup_learning_error) }
            runOnUiThread {
                if (!isDestroyed && requestEpoch == epoch) {
                    CompanionUiRepository.completeReadiness(this, query, result)
                    readinessQuery = null
                    learningSummary = learned
                }
            }
        }, "phone-setup-readiness").also { it.start() }
    }

    private fun refreshModels() {
        if (pickerWorker?.isAlive == true) return
        val token = getSharedPreferences("lc_config", MODE_PRIVATE).getString("voice_token", "") ?: ""
        if (!VoiceBridgeProtocol.validToken(token)) {
            pickerSnapshot = null
            pickerMessage = getString(R.string.setup_unpaired)
            return
        }
        val epoch = ++pickerEpoch
        pickerLoading = true
        pickerSnapshot = null // A failed refresh must not leave stale authority on screen.
        pickerMessage = getString(R.string.setup_picker_loading)
        pickerWorker = Thread({
            val result = try { pickerClient.load(token) } catch (_: Exception) { null }
            runOnUiThread {
                if (!isDestroyed && pickerEpoch == epoch) {
                    pickerLoading = false
                    pickerSnapshot = result
                    if (result == null) pickerMessage = getString(R.string.setup_picker_failed)
                    else {
                        // Preserve an unsaved draft across refresh; never replace it with the first model.
                        if (pickerSelection == null) pickerSelection = result.selection
                        pickerMessage = getString(R.string.setup_picker_loaded)
                    }
                }
            }
        }, "phone-model-picker").also { it.start() }
    }

    private fun openTermux() {
        val intent = packageManager.getLaunchIntentForPackage("com.termux")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app#installation"))
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, getString(R.string.setup_termux_error), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        requestEpoch++
        readinessQuery?.let { CompanionUiRepository.cancelReadiness(this, it) }
        readinessQuery = null
        pickerEpoch++
        pickerClient.close()
        pickerWorker?.interrupt()
        worker?.interrupt()
        super.onDestroy()
    }
}
