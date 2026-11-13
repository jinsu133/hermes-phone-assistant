package com.mist.connect

import android.app.Application
import android.content.Context
import android.content.res.Resources

/**
 * Locale-aware runtime text for code that has no Context (service status, notifications,
 * picker validation). Plain JVM unit tests have no resources and keep the Korean source text.
 */
object AppText {
    @Volatile private var resources: Resources? = null

    fun init(context: Context) { resources = context.applicationContext.resources }

    fun get(id: Int, fallback: String, vararg args: Any?): String {
        val res = resources ?: return fallback
        return try { if (args.isEmpty()) res.getString(id) else res.getString(id, *args) } catch (_: Exception) { fallback }
    }

    /** Wake-wait line; the service also compares against it to keep a rejection notice visible. */
    fun wakeWait(): String = WakeSettings.wakePhrase().let { get(R.string.rt_wake_wait, "듣기 ON · ‘$it’ 대기", it) }
}

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        WakeSettings.init(this)
    }
}
