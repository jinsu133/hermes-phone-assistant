package com.mist.connect

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Invoked by a registered HomeCommand gesture, never by render/restore/denial. */
internal object OverlaySettingsDialog {
    fun show(activity: Activity) {
        val optedIn = OptionalStatusOverlay.enabled(activity)
        val granted = OptionalStatusOverlay.allowed(activity)
        AlertDialog.Builder(activity)
            .setTitle(R.string.overlay_title)
            .setMessage(activity.getString(R.string.overlay_message,
                activity.getString(if (optedIn) R.string.on else R.string.overlay_off_default),
                activity.getString(if (granted) R.string.overlay_allowed else R.string.overlay_not_allowed)))
            .setNegativeButton(R.string.overlay_disable) { _, _ -> OptionalStatusOverlay.setEnabled(activity, false) }
            .setPositiveButton(R.string.overlay_enable) { _, _ ->
                OptionalStatusOverlay.setEnabled(activity, true)
                if (!OptionalStatusOverlay.allowed(activity)) Toast.makeText(activity,
                    R.string.overlay_permission_separate, Toast.LENGTH_LONG).show()
            }
            .setNeutralButton(R.string.overlay_permission_settings) { _, _ ->
                // Does NOT set opt-in and does NOT grant permission.
                try {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")))
                } catch (_: RuntimeException) {
                    Toast.makeText(activity, R.string.overlay_permission_failed, Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}
