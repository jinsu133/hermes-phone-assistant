package com.mist.connect

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import java.io.Closeable

/** Optional service-lifetime observer. Never starts a service or restores voice
 * consent. Main-thread ownership; no Activity retained, one lease/subscription,
 * one watchdog and one coalesced cross-thread OFF hint at most. */
internal object OptionalStatusOverlay {
    private const val PREFS = "optional_status_overlay"
    private const val ENABLED = "user_opt_in"
    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var attachment = 0L
    private var serviceAttached = false
    private var baseline: VoiceOwnerStamp? = null
    private var lastOffSequence = -1L
    private var controller: OptionalOverlayController? = null
    private var token = 0L
    private var lease: Closeable? = null
    private var subscription: Closeable? = null
    private var watchdog: Runnable? = null
    private enum class ReceiverState { UNREGISTERED, REGISTERED, UNKNOWN }
    private var receiverState = ReceiverState.UNREGISTERED
    // Keep the exact application context/handle through any uncertain platform call.
    // UNKNOWN is terminal for this process: toggles/reattach must not retry or replace it.
    private var receiverContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private val offGate = Any()
    private var pendingOff: Pair<Long, CompanionUiState>? = null
    private var offQueued = false
    private fun attempt(block: () -> Unit) { try { block() } catch (_: RuntimeException) { /* notification remains */ } }
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun allowed(context: Context): Boolean = try { Settings.canDrawOverlays(context) } catch (_: RuntimeException) { false }

    /** Called only by the existing service's onCreate; a new service must publish a
     * newer accepted generation/incarnation than any retained baseline. */
    fun attach(context: Context): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper())
        stopObservation()
        val mine = ++attachment
        app = context.applicationContext; serviceAttached = true; lastOffSequence = -1L
        try { baseline = CompanionUiRepository.read(context).owner } catch (_: RuntimeException) {
            serviceAttached = false
            return Closeable { }
        }
        attempt { reconcile() }
        return Closeable {
            if (attachment == mine) {
                serviceAttached = false; attachment++; stopObservation(); baseline = null
            }
        }
    }
    /** Explicit home-dialog gesture only. Persistence grants display, not voice. */
    fun setEnabled(context: Context, value: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, value).apply()
        // Preserve restart/OFF fences across opt-in toggles in the same service.
        if (!value) stopObservation() else attempt { reconcile() }
    }
    private fun screenSafe(context: Context): Boolean = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive
    } catch (_: RuntimeException) { false }
    private fun reconcile() {
        val context = app ?: return
        if (!serviceAttached || !enabled(context)) { stopObservation(); return }
        if (receiverState == ReceiverState.UNKNOWN) return
        if (subscription != null) return
        try {
            val c = controller ?: OptionalOverlayController(AndroidStatusOverlayWindow(context),
                { VoiceRequestService.readUiPublication()?.clearPublicPreview() }, SystemClock::elapsedRealtime)
                .also { controller = it }
            val initial = CompanionUiRepository.read(context)
            val currentToken = c.attach(initial.observationEpoch, baseline)
            token = currentToken
            val mine = attachment
            lease = CompanionUiRepository.observe(context)
            fun update(state: CompanionUiState) {
                if (mine != attachment || currentToken != token || !serviceAttached) return
                c.configure(enabled(context), allowed(context), screenSafe(context))
                c.offer(currentToken, state)
            }
            subscription = CompanionUiRepository.subscribeDisplay { update(it) }
            update(initial)
            watchdog = object : Runnable {
                override fun run() {
                    if (mine != attachment || currentToken != token || subscription == null) return
                    attempt {
                        c.configure(enabled(context), allowed(context), screenSafe(context))
                        c.refresh()
                    }
                    handler.postDelayed(this, 750L)
                }
            }.also { handler.postDelayed(it, 750L) }
            val privacyReceiver = object : BroadcastReceiver() {
                override fun onReceive(ignored: Context?, intent: Intent?) {
                    if (mine != attachment || currentToken != token) return
                    // Hide on screen-off before relying on a subsequent power/lock read.
                    if (intent?.action == Intent.ACTION_SCREEN_OFF)
                        c.configure(enabled(context), allowed(context), false)
                    else {
                        // Remove/recreate the same singleton after rotation/inset changes.
                        c.configure(enabled(context), allowed(context), false)
                        c.configure(enabled(context), allowed(context), screenSafe(context))
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            receiver = privacyReceiver
            receiverContext = context
            receiverState = ReceiverState.UNKNOWN
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(privacyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(privacyReceiver, filter)
            receiverState = ReceiverState.REGISTERED
        } catch (_: RuntimeException) { stopObservation() }
    }
    private fun stopObservation() {
        // Invalidate callbacks before releasing any registration.
        val retired = token; token = 0L
        attempt { controller?.detach(retired) }
        attempt { watchdog?.let { handler.removeCallbacks(it) } }; watchdog = null
        attempt { subscription?.close() }; subscription = null
        attempt { lease?.close() }; lease = null
        if (receiverState == ReceiverState.REGISTERED) {
            // Set uncertainty before the effect. No exception proves unregistration;
            // even a first-only/after-effect failure remains UNKNOWN without retries.
            receiverState = ReceiverState.UNKNOWN
            attempt {
                checkNotNull(receiverContext).unregisterReceiver(checkNotNull(receiver))
                receiverState = ReceiverState.UNREGISTERED
                receiver = null
                receiverContext = null
            }
        }
    }
    fun offRequested(state: CompanionUiState) {
        val mine = attachment
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyOff(mine, state); return
        }
        val enqueue = synchronized(offGate) {
            pendingOff = mine to state
            if (offQueued) false else { offQueued = true; true }
        }
        if (enqueue) handler.post {
            val hint = synchronized(offGate) { offQueued = false; pendingOff.also { pendingOff = null } }
            if (hint != null) applyOff(hint.first, hint.second)
        }
    }
    private fun applyOff(mine: Long, state: CompanionUiState) {
        if (mine != attachment || state.sequence < lastOffSequence) return
        val proposed = state.owner
        val old = baseline
        if (proposed != null && old != null && (proposed.processEpoch != old.processEpoch ||
            proposed.incarnation < old.incarnation ||
            (proposed.incarnation == old.incarnation && proposed.generation < old.generation))) return
        lastOffSequence = state.sequence
        // Keep a monotonic fence even when display is disabled and a late opt-in arrives.
        baseline = proposed ?: baseline
        attempt { controller?.offRequested(token, state) }
    }
}
