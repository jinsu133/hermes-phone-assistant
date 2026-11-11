package com.mist.connect
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
/** Deliberately never start control services from boot in this lab build. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { }
}
