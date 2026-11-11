package com.mist.connect

/** Display-only projection; caller serializes all methods on its UI thread.
 * Tokens fence detached callbacks, not voice authority. The M05 repository remains
 * the sole source/rebind authority. No callback can mint an accepted voice start. */
internal class OptionalOverlayController(private val window: Window,
    private val clearPrivatePreview: () -> Unit = {}, private val clock: () -> Long) {
    interface Window {
        fun show(title: String): Boolean
        fun hide()
        /** Optional visual hints. Defaults keep display-only doubles unchanged. */
        fun speaking(active: Boolean) {}
        fun recognizing(active: Boolean) {}
        fun visualPhase(phase: UiPhase) {}
        fun finalPreview(active: Boolean) {}
    }
    companion object {
        const val SOURCE_TTL_MS = 30_000L
        const val OBSERVATION_TTL_MS = 2_500L
    }
    private var serial = 0L
    private var attached = false
    private var epoch = ""
    private var sequence = -1L
    private var latest: CompanionUiState? = null
    private var owner: VoiceOwnerStamp? = null
    private var fence: VoiceOwnerStamp? = null
    private var unknownOff = false
    private var consent = false
    private var permission = false
    private var privateScreenSafe = false
    private var failed = false
    private var suppressedFinal: Pair<VoiceOwnerStamp, String>? = null

    private fun suppressCurrentPreview() {
        val s = latest
        val stamp = s?.owner
        val request = s?.requestId
        if (stamp != null && request != null) suppressedFinal = stamp to request
        clearPrivatePreview()
    }

    fun attach(observationEpoch: String, restartBaseline: VoiceOwnerStamp?): Long {
        hide()
        serial++; attached = true; epoch = observationEpoch; sequence = -1
        latest = null; owner = null; fence = restartBaseline; unknownOff = false
        failed = false
        return serial
    }
    fun detach(token: Long) {
        if (token != serial) return
        attached = false; serial++; latest = null; hide()
    }
    fun configure(optedIn: Boolean, systemPermission: Boolean, screenSafe: Boolean) {
        consent = optedIn; permission = systemPermission; privateScreenSafe = screenSafe
        if (!optedIn || !systemPermission || !screenSafe) suppressCurrentPreview()
        refresh()
    }
    fun offRequested(token: Long, current: CompanionUiState) {
        if (!attached || token != serial || current.observationEpoch != epoch || current.sequence < sequence) return
        val proposed = current.owner ?: owner ?: fence
        val previous = fence ?: owner
        if (proposed != null && previous != null && !sameGeneration(proposed, previous) &&
            !newerThanFence(proposed, previous)) return
        fence = proposed
        if (fence == null) unknownOff = true
        hide()
    }
    private fun sameGeneration(a: VoiceOwnerStamp, b: VoiceOwnerStamp) =
        sameSource(a, b) && a.generation == b.generation
    private fun sameSource(a: VoiceOwnerStamp, b: VoiceOwnerStamp) =
        a.processEpoch == b.processEpoch && a.incarnation == b.incarnation
    private fun newerThanFence(next: VoiceOwnerStamp, old: VoiceOwnerStamp): Boolean =
        next.processEpoch == old.processEpoch &&
            (next.incarnation > old.incarnation || (next.incarnation == old.incarnation && next.generation > old.generation))

    fun offer(token: Long, state: CompanionUiState) {
        if (!attached || token != serial || state.observationEpoch != epoch) return
        // M05 offRequested deliberately retains observation sequence. Do not discard it
        // as a duplicate. A subsequent pre-OFF delivery cannot undo this local fence.
        if (state.offPending && state.sequence >= sequence) offRequested(token, state)
        if (state.sequence <= sequence) { refresh(); return }
        val next = state.owner
        val previous = owner
        if (next != null && previous != null) {
            if (next.processEpoch != previous.processEpoch || next.incarnation < previous.incarnation) return
            if (sameSource(next, previous) && (next.generation < previous.generation ||
                (next.generation == previous.generation && next.sequence < previous.sequence))) return
        }
        sequence = state.sequence; latest = state; owner = next ?: owner
        if (state.service?.revoked == true || state.service?.mode == ServiceUiPublication.Mode.OFF)
            offRequested(token, state)
        refresh()
    }
    fun refresh() {
        val state = latest
        val s = state?.service
        val now = clock()
        val next = state?.owner
        val boundary = fence
        if (state?.locked == true) suppressCurrentPreview()
        val admissible = attached && !failed && consent && permission && privateScreenSafe &&
            state != null && s != null && next != null && !state.locked && !state.offPending &&
            !unknownOff && (boundary == null || newerThanFence(next, boundary)) &&
            s.acceptedStart && !s.revoked && s.mode != ServiceUiPublication.Mode.OFF &&
            s.waiting == ServiceUiPublication.Wait.NONE &&
            state.phase in setOf(UiPhase.PREPARING, UiPhase.WAKE_WAIT, UiPhase.CAPTURING,
                UiPhase.SUBMITTING, UiPhase.WAITING_REPLY, UiPhase.PLAYING, UiPhase.FOLLOW_UP) &&
            now >= state.observedAtMillis && now - state.observedAtMillis <= OBSERVATION_TTL_MS &&
            now >= s.sourceAtMillis && now - s.sourceAtMillis <= SOURCE_TTL_MS
        if (!admissible) { hide(); return }
        // Only an owner-fenced, sanitized public final preview or an allowlisted title.
        try {
            val blocked = suppressedFinal?.let { (stamp, request) ->
                next != null && sameGeneration(stamp, next) && request == state.requestId
            } == true
            val preview = s.publicFinalPreview?.takeUnless { blocked }
            val text = preview ?: CompanionUiText.title(checkNotNull(state))
            window.finalPreview(preview != null)
            if (!window.show(text)) { failed = true; hide() } else {
                window.visualPhase(state.phase)
                // Hints are presentation only; they never alter admissibility or voice authority.
                window.recognizing(state.phase == UiPhase.CAPTURING || state.phase == UiPhase.SUBMITTING)
                window.speaking(state.phase == UiPhase.PLAYING)
            }
        } catch (_: RuntimeException) { failed = true; hide() }
    }
    private fun hide() {
        try {
            window.speaking(false)
            window.recognizing(false)
            window.hide()
        } catch (_: RuntimeException) { failed = true }
    }
}
