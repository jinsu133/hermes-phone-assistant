package com.mist.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mist.connect.ui.theme.MistConnectTheme
import kotlin.math.roundToInt

/** User wake/stop words, sensitivity and on-device pronunciation learning. Not exported. */
class WakeSettingsActivity : ComponentActivity() {
    private var wake by mutableStateOf(WakeSettings.DEFAULT_WAKE)
    private var stop by mutableStateOf(WakeSettings.DEFAULT_STOP)
    private var sensitivity by mutableFloatStateOf(WakeSettings.DEFAULT_SENSITIVITY.toFloat())
    private var learnedWake by mutableStateOf(listOf<String>())
    private var learnedStop by mutableStateOf(listOf<String>())
    private var message by mutableStateOf("")
    private var training by mutableStateOf<WakeSettings.Kind?>(null)
    private var level by mutableFloatStateOf(0f)
    @Volatile private var alive = true
    private var pendingKind: WakeSettings.Kind? = null
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = pendingKind
        pendingKind = null
        if (!granted) message = getString(R.string.ws_mic_denied)
        else if (kind != null) train(kind)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.ws_title)
        wake = WakeSettings.wakePhrase(this)
        stop = WakeSettings.stopPhrase(this)
        sensitivity = WakeSettings.sensitivity(this).toFloat()
        learnedWake = WakeSettings.learned(WakeSettings.Kind.WAKE, this)
        learnedStop = WakeSettings.learned(WakeSettings.Kind.STOP, this)
        setContent { MistConnectTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onDestroy() { alive = false; super.onDestroy() }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        val busy = training != null
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ws_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ws_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = wake, enabled = !busy, singleLine = true,
                onValueChange = { if (it.length <= WakeSettings.MAX_PHRASE) { if (it.trim() != wake.trim()) learnedWake = emptyList(); wake = it } },
                label = { Text(stringResource(R.string.ws_wake_label)) },
                supportingText = { Text(stringResource(R.string.ws_default_hint, WakeSettings.DEFAULT_WAKE)) },
                modifier = Modifier.fillMaxWidth().testTag("ws-wake"))
            OutlinedTextField(value = stop, enabled = !busy, singleLine = true,
                onValueChange = { if (it.length <= WakeSettings.MAX_PHRASE) { if (it.trim() != stop.trim()) learnedStop = emptyList(); stop = it } },
                label = { Text(stringResource(R.string.ws_stop_label)) },
                supportingText = { Text(stringResource(R.string.ws_default_hint, WakeSettings.DEFAULT_STOP)) },
                modifier = Modifier.fillMaxWidth().testTag("ws-stop"))

            HorizontalDivider()
            Text(stringResource(R.string.ws_sensitivity, sensitivity.roundToInt()), style = MaterialTheme.typography.titleSmall)
            Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0f..100f, steps = 19,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("ws-sensitivity"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ws_sens_low), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ws_sens_high), style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text(stringResource(R.string.ws_train_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.ws_train_body), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { start(WakeSettings.Kind.WAKE) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("ws-train-wake")) { Text(stringResource(R.string.ws_train_wake)) }
            Text(stringResource(R.string.ws_learned_count, learnedWake.size), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { start(WakeSettings.Kind.STOP) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("ws-train-stop")) { Text(stringResource(R.string.ws_train_stop)) }
            Text(stringResource(R.string.ws_learned_count, learnedStop.size), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { learnedWake = emptyList(); learnedStop = emptyList(); message = "" }, enabled = !busy) {
                Text(stringResource(R.string.ws_reset_learned))
            }

            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("ws-message"))
            Button(onClick = { save() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("ws-save")) {
                Text(stringResource(R.string.ws_save))
            }
            TextButton(onClick = {
                wake = WakeSettings.DEFAULT_WAKE; stop = WakeSettings.DEFAULT_STOP
                sensitivity = WakeSettings.DEFAULT_SENSITIVITY.toFloat()
                learnedWake = emptyList(); learnedStop = emptyList(); message = ""
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_reset_defaults)) }
        }
    }

    private fun phraseOf(kind: WakeSettings.Kind) = (if (kind == WakeSettings.Kind.WAKE) wake else stop).trim()

    private fun start(kind: WakeSettings.Kind) {
        if (!WakeSettings.validPhrase(phraseOf(kind))) { message = getString(R.string.ws_invalid); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingKind = kind
            micRequest.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        train(kind)
    }

    private fun train(kind: WakeSettings.Kind) {
        val owner = Any()
        if (!MicrophoneOwner.claim(owner)) { message = getString(R.string.ws_mic_busy); return }
        val phrase = phraseOf(kind)
        val minTokens = if (kind == WakeSettings.Kind.WAKE) 3 else 1
        training = kind
        Thread({
            val found = mutableListOf<String>()
            try {
                val vocab = WakeSettings.vocab(applicationContext)
                WakeTrainer(applicationContext).use { trainer ->
                    for (round in 1..3) {
                        if (!alive) break
                        ui { message = getString(R.string.ws_train_prompt, phrase, round, 3) }
                        val pcm = trainer.record({ alive }) { v -> ui { level = v } }
                        if (pcm == null) { ui { message = getString(R.string.ws_train_too_short) }; Thread.sleep(900); continue }
                        val line = KwsTokens.learnedLine(trainer.decode(pcm), minTokens, vocab)
                        if (line == null) { ui { message = getString(R.string.ws_train_too_short) }; Thread.sleep(900); continue }
                        found += line
                        ui { message = getString(R.string.ws_train_heard, WakeSettings.readable(line)) }
                        Thread.sleep(700)
                    }
                }
                ui {
                    if (phraseOf(kind) == phrase) {
                        if (kind == WakeSettings.Kind.WAKE) learnedWake = (learnedWake + found).distinct().takeLast(WakeSettings.MAX_LEARNED)
                        else learnedStop = (learnedStop + found).distinct().takeLast(WakeSettings.MAX_LEARNED)
                    }
                    val total = if (kind == WakeSettings.Kind.WAKE) learnedWake.size else learnedStop.size
                    message = if (found.isEmpty()) getString(R.string.ws_train_too_short)
                        else getString(R.string.ws_learned_count, total)
                }
            } catch (e: Throwable) {
                ui { message = getString(R.string.ws_engine_failed) }
            } finally {
                MicrophoneOwner.release(owner)
                ui { training = null; level = 0f }
            }
        }, "wake-trainer").start()
    }

    private fun ui(block: () -> Unit) { if (alive) runOnUiThread { if (alive) block() } }

    private fun save() {
        val w = wake.trim(); val s = stop.trim()
        if (!WakeSettings.validPhrase(w) || !WakeSettings.validPhrase(s)) { message = getString(R.string.ws_invalid); return }
        if (WakeSettings.norm(w) == WakeSettings.norm(s)) { message = getString(R.string.ws_same_phrase); return }
        val vocab = WakeSettings.vocab(this)
        val wakeDefault = w.equals(WakeSettings.DEFAULT_WAKE, ignoreCase = true)
        if (!wakeDefault && KwsTokens.encode(w, vocab) == null && learnedWake.isEmpty()) {
            message = getString(R.string.ws_needs_training, w); return
        }
        if (KwsTokens.encode(s, vocab) == null && learnedStop.isEmpty() && !s.equals(WakeSettings.DEFAULT_STOP, ignoreCase = true)
            && !WakeSettings.koreanReadable(s)) {
            message = getString(R.string.ws_needs_training, s); return
        }
        WakeSettings.save(this, w, s, sensitivity.roundToInt(), learnedWake, learnedStop)
        wake = w; stop = s
        message = getString(R.string.ws_saved)
    }
}
