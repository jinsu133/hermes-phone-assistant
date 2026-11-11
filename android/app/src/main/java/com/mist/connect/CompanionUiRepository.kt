package com.mist.connect

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.UUID

/** Read-only Android observations. Never starts a service, network request or microphone. */
internal object CompanionUiRepository {
    private val handler = Handler(Looper.getMainLooper())
    private val store = UiObservationStore(UUID.randomUUID().toString()) { handler.post(it) }
    private val mutable = MutableStateFlow(store.read())
    val snapshots: StateFlow<CompanionUiState> = mutable.asStateFlow()
    private val flowSubscription = store.subscribe { mutable.value = it }
    private var users = 0
    private var app: Context? = null
    private var boundChannel: ServiceUiPublication? = null
    private var readinessVersion = 0L
    private var readinessAt = 0L
    private var readiness = SetupReadiness.Phase.UNCHECKED
    private var refreshQueued = false
    private var widgetLease: Closeable? = null
    private var widgetSubscription: Closeable? = null
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        synchronized(this) { readinessVersion++; readiness = SetupReadiness.Phase.UNCHECKED }
        requestRefresh()
    }
    private fun requestRefresh() {
        val schedule = synchronized(this) { if (refreshQueued) false else { refreshQueued = true; true } }
        if (schedule) handler.post {
            val context = synchronized(this) { refreshQueued = false; app }
            if (context != null) read(context)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            val context = synchronized(this@CompanionUiRepository) { if (users > 0) app else null } ?: return
            read(context)
            synchronized(this@CompanionUiRepository) { if (users > 0) handler.postDelayed(this, 750L) }
        }
    }
    @Synchronized private fun attach(context: Context) {
        if (app == null) {
            app = context.applicationContext
            app!!.getSharedPreferences("lc_config", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed)
        }
    }
    @Synchronized fun observe(context: Context): Closeable {
        attach(context)
        users++
        if (users == 1) handler.post(tick)
        var closed = false
        return Closeable { synchronized(this) {
            if (!closed) { closed = true; users--; if (users == 0) handler.removeCallbacks(tick) }
        } }
    }
    /** One process-local widget subscription, even with several installed widget IDs.
     * Keeps expiry/local observation ticking when voice service and Activities are OFF.
     * onDisabled releases it. Process death is handled by receiver update/expiry broadcast. */
    @Synchronized fun observeWidgets(context: Context, enabled: Boolean) {
        attach(context)
        if (enabled && widgetLease == null) {
            widgetLease = observe(context)
            val application = context.applicationContext
            widgetSubscription = store.subscribe { snapshot -> VoiceWidget.render(application, snapshot) }
        } else if (!enabled) {
            widgetSubscription?.close(); widgetSubscription = null
            widgetLease?.close(); widgetLease = null
        }
    }
    @Synchronized fun beginReadiness(context: Context): Long {
        attach(context)
        readiness = SetupReadiness.Phase.CHECKING
        readinessAt = SystemClock.elapsedRealtime()
        val version = ++readinessVersion
        read(context)
        VoiceWidget.scheduleExpiry(context, readinessAt + 30_000L)
        return version
    }
    @Synchronized fun completeReadiness(context: Context, version: Long, phase: SetupReadiness.Phase) {
        if (version != readinessVersion) return
        readiness = phase; readinessAt = SystemClock.elapsedRealtime()
        read(context)
        VoiceWidget.scheduleExpiry(context, readinessAt + 30_000L)
    }
    @Synchronized fun cancelReadiness(context: Context, version: Long) {
        if (version != readinessVersion) return
        readinessVersion++; readiness = SetupReadiness.Phase.UNCHECKED; read(context)
    }
    /** One optional display subscriber; same immutable M05 stream, no producer callback. */
    fun subscribeDisplay(listener: (CompanionUiState) -> Unit): Closeable = store.subscribe(listener)
    fun offRequested() {
        store.offRequested()
        OptionalStatusOverlay.offRequested(store.read())
    }
    @Synchronized fun read(context: Context): CompanionUiState {
        attach(context)
        val now = SystemClock.elapsedRealtime()
        val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val local = LocalUiFacts(now,
            locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked,
            microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            controlConnected = McpService.instance != null && LCAccessibilityService.instance != null,
            keysPresent = prefs.contains("voice_token") && prefs.contains("mcp_token"),
            readiness = readiness, readinessAt = readinessAt)
        val channel = VoiceRequestService.readUiPublication()
        val snapshot = channel?.read() // one atomic immutable slot; no owner-lock acquisition
        if (channel !== boundChannel) {
            boundChannel = channel
            store.rebind(channel, snapshot, local) // read-only, no consent/start restoration
        } else store.refresh(channel, snapshot, local)
        return store.read()
    }
}
