package com.mist.connect

/** Deliberate UI commands only. No APPROVE/REPLAY command exists. */
internal enum class HomeCommand {
    LOCK_SETTINGS, CONTROL_CONSENT, UNLOCK_PROBE, WAKE_DIAGNOSTIC, LISTEN_ON, LISTEN_OFF, PIN_WIDGET, SETUP, PERMISSIONS_OVERVIEW, EXPORT_UNIFIED, EXPORT_VOICE, START_REQUEST, SUBMIT_REQUEST, CANCEL_REQUEST, RECENT_REPLY, WAKE_PROBE, MIC_PROBE, STOP_PROBE, ACCESSIBILITY_SETTINGS, EXPORT_UI, START_MCP, ROTATE_UI_KEY, ROTATE_VOICE_KEY, UPDATE_ENDPOINT, STOP_EVERYTHING, OVERLAY_SETTINGS, WAKE_SETTINGS
}
internal enum class HomeSection { PRIMARY, EVERYDAY, MANUAL, CONNECTION, DIAGNOSTICS, ADVANCED }
internal data class HomeAction(val command: HomeCommand, val title: String, val section: HomeSection)

/** Registration and rendering cannot dispatch. Each gesture calls exactly one existing handler. */
internal class HomeCommandRouter {
    private val effects = mutableMapOf<HomeCommand, () -> Unit>()
    fun bind(command: HomeCommand, effect: () -> Unit) {
        check(!effects.containsKey(command)) { "duplicate_home_command" }
        effects[command] = effect
    }
    fun dispatch(command: HomeCommand) {
        checkNotNull(effects[command]) { "unregistered_home_command" }.invoke()
    }
}
