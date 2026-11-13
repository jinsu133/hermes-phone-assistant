package com.mist.connect

import android.content.Context

/** Display values only. Nothing here grants microphone, upload or approval authority. */
internal data class VoiceOwnerStamp(val processEpoch: String, val generation: Long, val sequence: Long, val incarnation: Long = 0)
internal enum class UiListening { UNKNOWN, OFF_OR_MANUAL, ENABLED_CAPTURE_UNKNOWN }
internal enum class UiRequest { IDLE_OR_PRE_ADMISSION, ACTIVE, UNKNOWN }
internal enum class UiPlayback { UNKNOWN, IDLE, PLAYING }
internal enum class UiBlocked { UNKNOWN, CLEAR, BLOCKED }
internal enum class UiPhase { UNOBSERVED, OBSERVING, OFF_NOT_CONFIRMED, PREPARING, WAKE_WAIT,
    CAPTURING, SUBMITTING, WAITING_REPLY, PLAYING, FOLLOW_UP, STOPPING, OFF_CONFIRMED, BLOCKED }
internal enum class UiEvidence { PUBLIC_FIELDS_ONLY, OWNER_SNAPSHOT }
internal data class OffFence(val processEpoch: String?, val incarnation: Long?, val generation: Long)

/** Local sequence and service sequence are intentionally independent. */
internal data class CompanionUiState(
    val observationEpoch: String, val sequence: Long = 0, val observedAtMillis: Long = 0,
    val owner: VoiceOwnerStamp? = null, val evidence: UiEvidence = UiEvidence.PUBLIC_FIELDS_ONLY,
    val readiness: SetupReadiness.Phase = SetupReadiness.Phase.UNCHECKED,
    val readinessObservedAtMillis: Long = 0,
    val listening: UiListening = UiListening.UNKNOWN, val request: UiRequest = UiRequest.UNKNOWN,
    val requestId: String? = null, val playback: UiPlayback = UiPlayback.UNKNOWN,
    val blocked: UiBlocked = UiBlocked.UNKNOWN, val phase: UiPhase = UiPhase.UNOBSERVED,
    val locked: Boolean = false, val microphoneGranted: Boolean = false, val controlConnected: Boolean = false,
    val serviceMessage: String = AppText.get(R.string.status_unknown, "상태 확인 전"), val offPending: Boolean = false,
    val service: ServiceUiPublication.Snapshot? = null, val serviceReceivedAtMillis: Long = 0,
    val offFence: OffFence? = null
) {
    fun withService(s: ServiceUiPublication.Snapshot?, receivedAt: Long = serviceReceivedAtMillis): CompanionUiState {
        if (s == null) return copy(service = null, owner = null, evidence = UiEvidence.PUBLIC_FIELDS_ONLY,
            listening = UiListening.UNKNOWN, request = UiRequest.UNKNOWN, requestId = null,
            playback = UiPlayback.UNKNOWN, blocked = UiBlocked.UNKNOWN, phase = UiPhase.UNOBSERVED,
            serviceMessage = AppText.get(R.string.status_unknown, "상태 전환 중 · 확인되지 않음"), serviceReceivedAtMillis = receivedAt)
        val p = when (s.phase) {
            ServiceUiPublication.Phase.UNKNOWN -> UiPhase.OBSERVING
            ServiceUiPublication.Phase.OFF -> UiPhase.OFF_CONFIRMED
            else -> UiPhase.valueOf(s.phase.name)
        }
        return copy(service = s, serviceReceivedAtMillis = receivedAt,
            owner = VoiceOwnerStamp(s.processEpoch, s.generation, s.sequence, s.incarnation), evidence = UiEvidence.OWNER_SNAPSHOT,
            listening = when (s.mode) {
                ServiceUiPublication.Mode.HANDSFREE -> UiListening.ENABLED_CAPTURE_UNKNOWN
                ServiceUiPublication.Mode.UNKNOWN -> UiListening.UNKNOWN
                else -> UiListening.OFF_OR_MANUAL
            }, request = if (s.requestId != null) UiRequest.ACTIVE else UiRequest.IDLE_OR_PRE_ADMISSION,
            requestId = s.requestId,
            playback = when(s.playback) {
                ServiceUiPublication.Playback.ACTIVE -> UiPlayback.PLAYING
                ServiceUiPublication.Playback.IDLE -> UiPlayback.IDLE
                else -> UiPlayback.UNKNOWN
            }, blocked = if (s.blocker == ServiceUiPublication.Blocker.NONE) UiBlocked.CLEAR else UiBlocked.BLOCKED,
            phase = p, serviceMessage = CompanionUiText.phase(p))
    }
}

internal object CompanionUiReducer {
    fun observe(previous: CompanionUiState, incoming: CompanionUiState): CompanionUiState {
        if (incoming.observationEpoch != previous.observationEpoch || incoming.sequence <= previous.sequence) return previous
        val old = previous.owner
        val next = incoming.owner
        val terminalRevival = previous.service?.revoked == true && incoming.service?.revoked == false && next?.generation == old?.generation
        val fresh = !terminalRevival && (old == null || (next != null && next.processEpoch == old.processEpoch && next.incarnation == old.incarnation &&
            (next.generation > old.generation || (next.generation == old.generation && next.sequence > old.sequence))))
        // Never discard new permission/lock/readiness facts for an unchanged or stale service stamp.
        val merged = if (fresh) incoming else incoming.withService(previous.service).copy(serviceReceivedAtMillis = previous.serviceReceivedAtMillis)
        val fence = previous.offFence ?: return merged.copy(offPending = false, offFence = null)
        val s = merged.service
        val newerAcceptedStart = s != null && s.acceptedStart &&
            s.processEpoch == fence.processEpoch && s.incarnation == fence.incarnation && s.generation > fence.generation
        if (newerAcceptedStart || s?.revoked == true) return merged.copy(offPending = false, offFence = null)
        return fenced(merged, fence)
    }
    /** Only the repository's explicit channel replacement calls this, never an event. */
    fun rebind(previous: CompanionUiState, incoming: CompanionUiState): CompanionUiState = incoming.copy(offFence = null, offPending = false)
    private fun fenced(state: CompanionUiState, fence: OffFence) = state.copy(
        offFence = fence, offPending = true, listening = UiListening.OFF_OR_MANUAL,
        request = UiRequest.UNKNOWN, requestId = null,
        phase = if (state.blocked == UiBlocked.BLOCKED) UiPhase.BLOCKED else UiPhase.STOPPING,
        serviceMessage = AppText.get(R.string.status_off_pending, "OFF 요청 전달 중 · 종료 확인 전"))
    fun offRequested(previous: CompanionUiState): CompanionUiState = fenced(previous,
        OffFence(previous.owner?.processEpoch, previous.owner?.incarnation, previous.owner?.generation ?: -1))
}

internal object CompanionUiText {
    // Reducer messages are retained verbatim for provenance. Only the presentation API below is localized.
    fun phase(phase: UiPhase): String = when (phase) {
        UiPhase.UNOBSERVED, UiPhase.OBSERVING -> AppText.get(R.string.status_unknown, "상태 전환 중 · 확인되지 않음")
        UiPhase.PREPARING -> AppText.get(R.string.status_preparing, "음성 요청 준비 중")
        UiPhase.WAKE_WAIT -> WakeSettings.wakePhrase().let { AppText.get(R.string.status_wake, "‘$it’ 호출 대기 중", it) }
        UiPhase.CAPTURING -> AppText.get(R.string.status_capturing, "요청 녹음 중")
        UiPhase.SUBMITTING -> AppText.get(R.string.status_submitting, "음성 요청 전송 중")
        UiPhase.WAITING_REPLY -> AppText.get(R.string.status_waiting, "답변 대기 중")
        UiPhase.PLAYING -> AppText.get(R.string.status_playing, "재생 장치 시작 확인 · 실제 청취와 별개")
        UiPhase.FOLLOW_UP -> AppText.get(R.string.status_followup, "이어 말하기 대기 중")
        UiPhase.STOPPING -> AppText.get(R.string.status_stopping, "중단 요청 · 정리 확인 중")
        UiPhase.OFF_CONFIRMED -> AppText.get(R.string.status_off_confirmed, "듣기 OFF · 소유자 정리 확인")
        UiPhase.OFF_NOT_CONFIRMED -> AppText.get(R.string.status_off_uncertain, "듣기 OFF · 정리 확인 전")
        UiPhase.BLOCKED -> AppText.get(R.string.status_blocked, "중단됨 · 차단 원인 확인 필요")
    }
    private fun phaseId(phase: UiPhase): Int = when (phase) {
        UiPhase.UNOBSERVED, UiPhase.OBSERVING -> R.string.status_unknown
        UiPhase.PREPARING -> R.string.status_preparing
        UiPhase.WAKE_WAIT -> R.string.status_wake
        UiPhase.CAPTURING -> R.string.status_capturing
        UiPhase.SUBMITTING -> R.string.status_submitting
        UiPhase.WAITING_REPLY -> R.string.status_waiting
        UiPhase.PLAYING -> R.string.status_playing
        UiPhase.FOLLOW_UP -> R.string.status_followup
        UiPhase.STOPPING -> R.string.status_stopping
        UiPhase.OFF_CONFIRMED -> R.string.status_off_confirmed
        UiPhase.OFF_NOT_CONFIRMED -> R.string.status_off_uncertain
        UiPhase.BLOCKED -> R.string.status_blocked
    }
    // Compatibility for overlay, widget and state tests; UI callers use locale-aware overloads.
    fun title(state: CompanionUiState): String = when {
        state.service?.blocker == ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN -> AppText.get(R.string.status_cleanup_uncertain, "중단됨 · 정리 상태 확인 필요")
        state.offPending -> AppText.get(R.string.status_off_pending, "OFF 요청 중 · 종료 확인 전")
        state.service?.waiting == ServiceUiPublication.Wait.APPROVAL -> AppText.get(R.string.status_approval, "민감 작업 승인 대기 · 기존 확인 화면에서 결정")
        state.service?.waiting == ServiceUiPublication.Wait.UNLOCK -> AppText.get(R.string.status_unlock, "잠금 해제 대기 · 기기에서 직접 인증")
        state.phase == UiPhase.UNOBSERVED || state.phase == UiPhase.OBSERVING -> AppText.get(R.string.status_unknown, "상태 전환 중 · 확인되지 않음")
        else -> phase(state.phase)
    }
    fun details(state: CompanionUiState): String = buildString {
        append(if (state.locked) "잠금 해제 필요" else "잠금 해제됨")
        append(if (state.controlConnected) " · 조작 서비스 관측됨 (인증 증거 아님)" else " · 조작 연결 확인 필요")
        if (!state.microphoneGranted) append(" · 마이크 권한 확인 필요")
        append("\n")
        append(when (state.readiness) {
            SetupReadiness.Phase.UNCHECKED -> "호스트 지원 상태 미확인 또는 30초 관측 만료"
            SetupReadiness.Phase.CHECKING -> "호스트 지원 상태 확인 중"
            SetupReadiness.Phase.UNPAIRED -> "앱 연결 키 준비 필요"
            SetupReadiness.Phase.UNREACHABLE -> "호스트 연결·인증 미확인"
            SetupReadiness.Phase.HOST_UNSUPPORTED -> "호스트 음성 계약 미지원 · 우회 없음"
            SetupReadiness.Phase.AVAILABLE -> "최근 조회에서 B 모드 지원 확인 · 30초 유효 · 실제 동작 검증과 별개"
        })
        if (state.readinessObservedAtMillis > 0)
            append("\n호스트 조회: 부팅 후 ${state.readinessObservedAtMillis / 1000}초 · 절전 중 갱신 지연 가능")
        val s = state.service
        if (s == null) append("\n서비스 상태 확인되지 않음 · OFF 완료 증거 없음")
        else {
            append("\n캡처 ${s.capture} · 재생 ${s.playback}")
            append("\n정리: 캡처 ${s.captureCleanup} / 재생 ${s.playbackCleanup} / 로컬 ${s.localCleanup} / 원격 ${s.remoteCleanup}")
            if (!s.remoteJoined) append(" · 원격 작업자 join 미확인")
            if (s.blocker != ServiceUiPublication.Blocker.NONE) append("\n차단: ${s.blocker}")
            append("\n표시 생성 시 서비스 관측 경과 ${maxOf(0, state.observedAtMillis - s.sourceAtMillis) / 1000}초")
        }
    }
    fun title(context: Context, state: CompanionUiState): String = context.getString(when {
        state.service?.blocker == ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN -> R.string.status_cleanup_uncertain
        state.offPending -> R.string.status_off_pending
        state.service?.waiting == ServiceUiPublication.Wait.APPROVAL -> R.string.status_approval
        state.service?.waiting == ServiceUiPublication.Wait.UNLOCK -> R.string.status_unlock
        else -> phaseId(state.phase)
    }, WakeSettings.wakePhrase(context))
    fun nextStep(context: Context, state: CompanionUiState): String = (when {
        state.service?.blocker == ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN -> R.string.step_diagnostics
        state.offPending || state.phase == UiPhase.OFF_NOT_CONFIRMED || state.phase == UiPhase.STOPPING -> R.string.step_wait_off
        state.service?.waiting == ServiceUiPublication.Wait.APPROVAL -> R.string.step_approval
        state.service?.waiting == ServiceUiPublication.Wait.UNLOCK || state.locked -> R.string.step_unlock
        !state.microphoneGranted -> R.string.step_microphone
        state.readiness == SetupReadiness.Phase.UNPAIRED -> R.string.step_pairing
        state.blocked == UiBlocked.BLOCKED -> R.string.step_diagnostics
        state.phase == UiPhase.UNOBSERVED || state.phase == UiPhase.OBSERVING -> R.string.step_observe
        state.phase == UiPhase.OFF_CONFIRMED -> R.string.step_listen
        else -> R.string.step_ready
    }.let { id -> context.getString(id, if (id == R.string.step_ready) WakeSettings.stopPhrase(context) else WakeSettings.wakePhrase(context)) })
    fun details(context: Context, state: CompanionUiState): String = buildString {
        append(context.getString(if (state.locked) R.string.detail_locked else R.string.detail_unlocked))
        append(" · ")
        append(context.getString(if (state.controlConnected) R.string.detail_control_observed else R.string.detail_control_unconfirmed))
        if (!state.microphoneGranted) { append(" · "); append(context.getString(R.string.detail_mic_permission)) }
        append("\n")
        append(context.getString(when (state.readiness) {
            SetupReadiness.Phase.UNCHECKED -> R.string.detail_readiness_unknown
            SetupReadiness.Phase.CHECKING -> R.string.detail_readiness_checking
            SetupReadiness.Phase.UNPAIRED -> R.string.detail_readiness_unpaired
            SetupReadiness.Phase.UNREACHABLE -> R.string.detail_readiness_unreachable
            SetupReadiness.Phase.HOST_UNSUPPORTED -> R.string.detail_readiness_unsupported
            SetupReadiness.Phase.AVAILABLE -> R.string.detail_readiness_available
        }))
        if (state.readinessObservedAtMillis > 0)
            append("\n" + context.getString(R.string.detail_host_age, state.readinessObservedAtMillis / 1000))
        val s = state.service
        if (s == null) append("\n" + context.getString(R.string.detail_service_unknown))
        else {
            append("\n" + context.getString(R.string.detail_capture_playback, s.capture, s.playback))
            append("\n" + context.getString(R.string.detail_cleanup, s.captureCleanup, s.playbackCleanup, s.localCleanup, s.remoteCleanup))
            if (!s.remoteJoined) append(" · " + context.getString(R.string.detail_remote_join))
            if (s.blocker != ServiceUiPublication.Blocker.NONE) append("\n" + context.getString(R.string.detail_blocker, s.blocker))
            append("\n" + context.getString(R.string.detail_service_age, maxOf(0, state.observedAtMillis - s.sourceAtMillis) / 1000))
        }
    }
}
