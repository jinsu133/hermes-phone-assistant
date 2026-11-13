package com.mist.connect

/**
 * Plain-language presentation of the companion state for the home screen and widget.
 * Display only: every value here is derived from [CompanionUiState] and never grants
 * microphone, upload or approval authority.
 */
internal enum class HomeTone { READY, ACTIVE, ATTENTION, PROBLEM, NEUTRAL }

internal enum class ReadyLevel { OK, MISSING, UNKNOWN }

internal data class ReadyItem(val label: Int, val value: Int, val level: ReadyLevel, val fix: HomeCommand?)

internal data class HomeFix(val text: Int, val command: HomeCommand)

internal object HomeStatus {
    fun blocker(state: CompanionUiState): ServiceUiPublication.Blocker? =
        state.service?.blocker?.takeIf { it != ServiceUiPublication.Blocker.NONE }

    fun isProblem(state: CompanionUiState): Boolean =
        state.blocked == UiBlocked.BLOCKED || blocker(state) == ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN

    /** True while the hands-free owner may be listening, so the main button should stop it. */
    fun listeningActive(state: CompanionUiState): Boolean = !state.offPending && !isProblem(state) &&
        (state.listening == UiListening.ENABLED_CAPTURE_UNKNOWN || state.phase in ACTIVE_PHASES)

    fun tone(state: CompanionUiState): HomeTone = when {
        isProblem(state) -> HomeTone.PROBLEM
        state.service?.waiting == ServiceUiPublication.Wait.APPROVAL ||
            state.service?.waiting == ServiceUiPublication.Wait.UNLOCK -> HomeTone.ATTENTION
        state.offPending || state.phase == UiPhase.STOPPING -> HomeTone.NEUTRAL
        state.phase in ACTIVE_PHASES -> HomeTone.ACTIVE
        state.phase == UiPhase.OFF_CONFIRMED && state.microphoneGranted &&
            state.readiness != SetupReadiness.Phase.UNPAIRED -> HomeTone.READY
        else -> HomeTone.NEUTRAL
    }

    /** The specific reason a start was refused, in words, or null when nothing is blocked. */
    fun blockerText(state: CompanionUiState): Int? {
        if (!isProblem(state)) return null
        return when (blocker(state)) {
            ServiceUiPublication.Blocker.MIC_PERMISSION -> R.string.blocker_mic_permission
            ServiceUiPublication.Blocker.PAIRING_MISSING -> R.string.blocker_pairing_missing
            ServiceUiPublication.Blocker.MIC_BUSY -> R.string.blocker_mic_busy
            ServiceUiPublication.Blocker.OWNER_BUSY -> R.string.blocker_owner_busy
            ServiceUiPublication.Blocker.FOREGROUND_DENIED -> R.string.blocker_foreground_denied
            ServiceUiPublication.Blocker.HOST_UNAVAILABLE -> R.string.blocker_host_unavailable
            ServiceUiPublication.Blocker.MODEL_ROUTE_INVALID -> R.string.blocker_model_route_invalid
            ServiceUiPublication.Blocker.PROTOCOL_UNSUPPORTED -> R.string.blocker_protocol_unsupported
            ServiceUiPublication.Blocker.CONTROLLER_REVOKED -> R.string.blocker_controller_revoked
            ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN -> R.string.blocker_cleanup_uncertain
            ServiceUiPublication.Blocker.REQUEST_FAILED -> R.string.blocker_request_failed
            else -> R.string.blocker_unknown
        }
    }

    /** One suggested fix for the current problem. The command is an existing home action. */
    fun fix(state: CompanionUiState): HomeFix? {
        if (!isProblem(state)) {
            if (!state.microphoneGranted && !listeningActive(state))
                return HomeFix(R.string.fix_open_permissions, HomeCommand.PERMISSIONS_OVERVIEW)
            if (state.readiness == SetupReadiness.Phase.UNPAIRED)
                return HomeFix(R.string.fix_export_pairing, HomeCommand.EXPORT_UNIFIED)
            return null
        }
        return when (blocker(state)) {
            ServiceUiPublication.Blocker.MIC_PERMISSION -> HomeFix(R.string.fix_open_permissions, HomeCommand.PERMISSIONS_OVERVIEW)
            ServiceUiPublication.Blocker.PAIRING_MISSING -> HomeFix(R.string.fix_export_pairing, HomeCommand.EXPORT_UNIFIED)
            ServiceUiPublication.Blocker.HOST_UNAVAILABLE,
            ServiceUiPublication.Blocker.MODEL_ROUTE_INVALID,
            ServiceUiPublication.Blocker.PROTOCOL_UNSUPPORTED -> HomeFix(R.string.fix_check_host, HomeCommand.SETUP)
            ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN -> HomeFix(R.string.fix_stop_all, HomeCommand.STOP_EVERYTHING)
            null -> null
            else -> HomeFix(R.string.fix_retry, HomeCommand.LISTEN_ON)
        }
    }

    fun readiness(state: CompanionUiState): List<ReadyItem> = listOf(
        ReadyItem(R.string.ready_mic,
            if (state.microphoneGranted) R.string.ready_ok else R.string.ready_missing,
            if (state.microphoneGranted) ReadyLevel.OK else ReadyLevel.MISSING,
            if (state.microphoneGranted) null else HomeCommand.PERMISSIONS_OVERVIEW),
        ReadyItem(R.string.ready_control,
            if (state.controlConnected) R.string.ready_ok else R.string.ready_missing,
            if (state.controlConnected) ReadyLevel.OK else ReadyLevel.MISSING,
            if (state.controlConnected) null else HomeCommand.ACCESSIBILITY_SETTINGS),
        when (state.readiness) {
            SetupReadiness.Phase.AVAILABLE -> ReadyItem(R.string.ready_host, R.string.ready_ok, ReadyLevel.OK, null)
            SetupReadiness.Phase.CHECKING -> ReadyItem(R.string.ready_host, R.string.ready_checking, ReadyLevel.UNKNOWN, null)
            SetupReadiness.Phase.UNPAIRED -> ReadyItem(R.string.ready_host, R.string.ready_unpaired, ReadyLevel.MISSING, HomeCommand.EXPORT_UNIFIED)
            SetupReadiness.Phase.UNREACHABLE -> ReadyItem(R.string.ready_host, R.string.ready_unreachable, ReadyLevel.MISSING, HomeCommand.SETUP)
            SetupReadiness.Phase.HOST_UNSUPPORTED -> ReadyItem(R.string.ready_host, R.string.ready_unsupported, ReadyLevel.MISSING, HomeCommand.SETUP)
            SetupReadiness.Phase.UNCHECKED -> ReadyItem(R.string.ready_host, R.string.ready_unknown, ReadyLevel.UNKNOWN, HomeCommand.SETUP)
        },
        ReadyItem(R.string.ready_lock,
            if (state.locked) R.string.ready_locked else R.string.ready_unlocked,
            if (state.locked) ReadyLevel.UNKNOWN else ReadyLevel.OK,
            HomeCommand.LOCK_SETTINGS),
    )

    private val ACTIVE_PHASES = setOf(UiPhase.PREPARING, UiPhase.WAKE_WAIT, UiPhase.CAPTURING,
        UiPhase.SUBMITTING, UiPhase.WAITING_REPLY, UiPhase.PLAYING, UiPhase.FOLLOW_UP)
}
