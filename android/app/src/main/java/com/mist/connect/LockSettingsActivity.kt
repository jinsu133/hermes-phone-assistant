package com.mist.connect

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-driven settings navigation only. Never reads credentials or changes trust policy. */
class LockSettingsActivity : Activity() {
    private lateinit var state: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 48)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        state = TextView(this).apply { textSize = 20f; setTextColor(android.graphics.Color.BLACK) }
        layout.addView(state)
        layout.addView(TextView(this).apply {
            text = "\n" + getString(R.string.lock_title) + "\n\n" + getString(R.string.lock_body) + "\n"
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
        })
        layout.addView(Button(this).apply {
            text = getString(R.string.lock_open_security)
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
        layout.addView(Button(this).apply { text = getString(R.string.lock_back); setOnClickListener { finish() } })
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
    override fun onResume() {
        super.onResume()
        val kg = getSystemService(KeyguardManager::class.java)
        state.text = getString(if (kg.isDeviceLocked) R.string.lock_state_locked else R.string.lock_state_unlocked)
        VoiceWidget.update(this)
    }
}
