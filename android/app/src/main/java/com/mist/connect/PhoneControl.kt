package com.mist.connect

import android.content.Context
import android.content.Intent

/** An app update or process restart stops the phone-control service while accessibility
 * stays on, and home then reported accessibility as missing. The service comes back only
 * when the user already consented to phone control and accessibility is connected; it is
 * tried when accessibility connects (after an update that comes after home opens) and from home. */
internal object PhoneControl {
    /** Returns true when it started the service. */
    fun restore(context: Context): Boolean {
        if (McpService.instance != null || LCAccessibilityService.instance == null) return false
        if (!context.getSharedPreferences("handsfree", Context.MODE_PRIVATE).getBoolean("control_consent", false)) return false
        return try {
            context.startForegroundService(Intent(context, McpService::class.java)); true
        } catch (_: RuntimeException) { false }
    }
}
