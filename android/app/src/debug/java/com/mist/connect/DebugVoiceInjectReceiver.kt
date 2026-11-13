package com.mist.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/** Debug-only scenario harness. adb (holder of DUMP) names a WAV already placed in the app's
 * private files/debug-input; it is handed to the running hands-free capture as one utterance
 * after the wake word. It never starts the service, never touches confirmation listening. */
class DebugVoiceInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: ""
        val result = if (!Regex("[a-z0-9_-]{1,40}").matches(name)) "bad_name" else try {
            val dir = File(context.filesDir, "debug-input")
            val file = File(dir, "$name.wav")
            if (file.canonicalFile.parentFile != dir.canonicalFile || !file.isFile) "missing"
            else if (file.length() > InjectedUtterance.MAX_BYTES + 4096L) "too_large"
            else {
                val bytes = file.readBytes()
                val pcm = InjectedUtterance.parseWav(bytes)
                bytes.fill(0)
                if (pcm == null) "bad_wav" else if (InjectedUtterance.offer(pcm)) "queued" else "rejected"
            }
        } catch (e: Exception) { "error_" + e.javaClass.simpleName }
        Log.i("HermesInject", "inject name=$name result=$result")
        resultData = result
    }
}
