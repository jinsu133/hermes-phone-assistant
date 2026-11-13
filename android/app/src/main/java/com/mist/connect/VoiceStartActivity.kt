package com.mist.connect

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** A visible user gesture grants Android's while-in-use microphone startup. */
class VoiceStartActivity : Activity() {
    private var launched = false
    override fun onCreate(state: Bundle?) { super.onCreate(state) }
    override fun onResume() {
        super.onResume()
        if (launched) return
        launched = true
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 710); return }
        begin()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) begin() else finish()
    }
    private fun begin() {
        val prefs = getSharedPreferences("handsfree", MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false)) {
            AlertDialog.Builder(this).setTitle(R.string.start_consent_title)
                .setMessage(R.string.start_consent_message)
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .setPositiveButton(R.string.start_consent_accept) { _, _ -> prefs.edit().putBoolean("consent", true).apply(); launch() }
                .setOnCancelListener { finish() }.show()
        } else launch()
    }
    private fun launch() {
        try {
            if (getSharedPreferences("handsfree", MODE_PRIVATE).getBoolean("control_consent", false))
                startForegroundService(Intent(this, McpService::class.java))
            startForegroundService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.START_HANDSFREE))
        }
        catch (_: RuntimeException) { android.widget.Toast.makeText(this, getString(R.string.start_failed), android.widget.Toast.LENGTH_LONG).show() }
        finish()
    }
}
