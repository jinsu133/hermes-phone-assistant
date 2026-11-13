package com.mist.connect

import android.app.Activity
import android.app.AlertDialog

/** Theme choice; every surface (app, sub-screens, overlay, widget, Nous girl) follows it. */
internal object ThemeDialog {
    fun show(activity: Activity) {
        val themes = AppThemeId.entries
        val labels = themes.map { activity.getString(it.label) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(labels, themes.indexOf(AppTheme.current)) { dialog, which ->
                dialog.dismiss()
                val chosen = themes[which]
                if (chosen != AppTheme.current) AppTheme.set(activity, chosen)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
