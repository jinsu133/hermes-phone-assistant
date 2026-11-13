package com.mist.connect

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.TextView

/** User-driven settings navigation only. Never reads credentials or changes trust policy. */
class LockSettingsActivity : Activity() {
    private lateinit var state: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = MistViews.screen(this)
        state = MistViews.title(this)
        layout.addView(state)
        layout.addView(MistViews.card(this, getString(R.string.lock_title) + "\n\n" + getString(R.string.lock_body)))
        layout.addView(MistViews.button(this, getString(R.string.lock_open_security), primary = true) {
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        layout.addView(MistViews.button(this, getString(R.string.lock_back), primary = false) { finish() })
    }
    override fun onResume() {
        super.onResume()
        val kg = getSystemService(KeyguardManager::class.java)
        state.text = getString(if (kg.isDeviceLocked) R.string.lock_state_locked else R.string.lock_state_unlocked)
        VoiceWidget.update(this)
    }
}
