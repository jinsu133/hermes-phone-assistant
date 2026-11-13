package com.mist.connect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, bounded push-to-talk path into the configured Hermes conversation. */
class VoiceRequestService : Service() {
    companion object {
        // Legacy controller trial uses a dedicated fixture credential, never voice_token.
        // Normal streaming below uses private opt-in + authenticated existing pairing.
        internal var controllerTrialFactory: ((SourceServicePlaybackAdapter) -> SourceControllerTransport)? = null
        internal const val CONTROLLER_TRIAL = "source_controller_trial"
        // Init-only source fixture factory: never read from Intent/preferences/pairing.
        @Volatile internal var syntheticTrialResult: SourceSyntheticTrial.Result? = null
        internal var syntheticTrialFactory: ((SourceServicePlaybackAdapter) -> SourceSyntheticTrial)? = null
        internal const val SYNTHETIC_TRIAL = "com.mist.connect.hermeslab.SOURCE_SYNTHETIC_TRIAL"
        const val START_HANDSFREE = "com.mist.connect.hermeslab.START_HANDSFREE"
        @Volatile var handsfreeActive = false
            private set
        const val START_REQUEST = "com.mist.connect.hermeslab.START_VOICE_REQUEST"
        const val SUBMIT_REQUEST = "com.mist.connect.hermeslab.SUBMIT_VOICE_REQUEST"
        const val CANCEL_REQUEST = "com.mist.connect.hermeslab.CANCEL_VOICE_REQUEST"
        const val CHANNEL = "voice_request"
        const val NOTIFICATION = 401
        private const val SAMPLE_RATE = 16000
        private const val CAPTURE_LIMIT_MS = 30_000L
        internal const val REQUEST_LIMIT_MS = 9 * 60_000L
        /** Re-stamp status well inside the overlay source TTL (30 s) while the service runs. */
        internal const val HEARTBEAT_MS = 10_000L
        private const val REPLY_WAIT_MS = 540_000L
        private const val TTS_WAIT_MS = 180_000L
        private const val MIN_PCM_BYTES = 3200

        @Volatile var status = AppText.get(R.string.rt_request_off, "음성 요청 OFF")
            private set
        private val cancellation = ScopedVoiceCancellation()
        @Volatile private var uiChannel: ServiceUiPublication? = null
        internal fun readUiPublication(): ServiceUiPublication? = uiChannel
        @Volatile private var remoteCancelWorker: Thread? = null
        // Same retained worker's evidence consumer, never a new cancellation authority.
        // Guarded by cancellation; captures the originating service/display generation.
        private var remoteCancelJoined: (() -> Unit)? = null
        private var remoteCancelJoinedWorker: Thread? = null // exact physical join certificate
        @Volatile private var remoteCancelLocalLease: Any? = null // cleared only by exact local release
        private var remoteCancelDestroyJoin: (() -> Unit)? = null // originating owner only
        // Independently tracked observer; never a cancellation/effect worker or local proof.
        private enum class DestroyJoinOutcome { PENDING, SUCCEEDED, FAILED }
        private class DestroyRemoteJoin(val target: Thread) {
            var worker: Thread? = null
            var outcome = DestroyJoinOutcome.PENDING // finalized under the settlement gate
            var failure: String? = null // sticky, sanitized evidence; guarded by cancellation
        }
        private var destroyRemoteJoin: DestroyRemoteJoin? = null
        private fun destroyRemoteJoinBlocked(): Boolean = synchronized(cancellation) {
            destroyRemoteJoin?.let { it.outcome != DestroyJoinOutcome.SUCCEEDED || it.failure != null || it.worker?.state != Thread.State.TERMINATED } ?: false
        }
        // Guarded by cancellation; queued status from a retired service cannot win.
        private var cleanupStatusOwner: Any? = null
        @Volatile var activeRequestId = ""
            private set
        @Volatile var lastReplyText = ""
            private set
    }

    private val uiPublication = ServiceUiPublication(cancellation, SystemClock::elapsedRealtime)
    @Volatile private var uiScope = uiPublication.sessionScope()
    // Physical remote-worker join evidence, not worker finally/isAlive inference.
    private var uiRemoteJoined = true // guarded by cancellation
    private fun refuseUi(reason: ServiceUiPublication.Blocker) {
        uiPublication.refused(reason)
        synchronized(cancellation) {
            val previous = uiChannel?.read()
            // Preserve a live/uncertain retained owner. A rejected NEW admission may replace
            // only unstarted or actually settled previous display channels.
            if (uiChannel == null || previous?.acceptedStart == false ||
                (previous?.revoked == true && previous.localCleanup == ServiceUiPublication.Cleanup.CONFIRMED &&
                    previous.remoteCleanup == ServiceUiPublication.Cleanup.CONFIRMED && previous.remoteJoined &&
                    previous.blocker != ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN)) uiChannel = uiPublication
        }
    }
    private var handsfree = false
    private val turnStopped = AtomicBoolean(false)
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val widgetTick = object : Runnable {
        override fun run() {
            VoiceWidget.update(this@VoiceRequestService)
            if (running.get()) {
                // Status liveness for the overlay's source TTL; display only.
                uiPublication.heartbeat(uiScope, HEARTBEAT_MS)
                main.postDelayed(this, 1500)
            }
        }
    }
    private val running = AtomicBoolean(false)
    private val submitRequested = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var connection: HttpURLConnection? = null
    // One retained owner for buffered and explicit paired streaming; no unrestricted LIVE factory.
    private val playback = SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE, SystemClock::elapsedRealtime)
    init {
        // One bounded sanitized async journal and MCP tail; no raw-event logcat passthrough.
        // Adapter events carry stage/error/local ids only: no speech text or token.
        playback.setTraceSink { event ->
            VoiceDiagnostics.trace(event)
            val stage = Regex("stage=(\\S+)").find(event)?.groupValues?.get(1).orEmpty()
            val error = Regex("error=(\\S+)").find(event)?.groupValues?.get(1).orEmpty()
            if (stage.isNotEmpty() && error.isNotEmpty() && error != "none") {
                VoiceDiagnostics.playbackFailed(stage, error)
            }
        }
    }

    private var statusOverlayLease: java.io.Closeable? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(cancellation) {
            // A recreated OFF-only instance cannot erase prior cleanup uncertainty.
            if (uiChannel == null) uiChannel = uiPublication
        }
        // Context is only valid from onCreate; the constructor has no base context yet.
        VoiceDiagnostics.attachLogFile(filesDir)
        statusOverlayLease = OptionalStatusOverlay.attach(this)
    }
    @Volatile private var controllerDriver: SourceControllerDriver? = null
    @Volatile private var normalStream: NormalVoiceStream? = null
    @Volatile private var requestRender: NormalVoiceStream.Request? = null
    @Volatile private var pendingFinalContent: NormalVoiceStream.ContentDone? = null
    @Volatile private var acceptedRenderCancellation: ScopedVoiceCancellation.Scope? = null
    @Volatile private var requestAbsoluteDeadline = 0L
    private val contentEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private var normalStreaming = false
    private val playbackEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val consentRevision = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lifecycleThread: Thread? = null
    @Volatile private var activeStartId = 0
    // Guarded by cancellation. Local completion is evidence from finish(), not isAlive.
    private var localCleanupStartId: Int? = null
    private val microphoneLease = Any()
    private var cleanupReleasePending = false
    private var offStatusOwner: Any? = null
    private var offCompletionMessage: String? = null
    private var offPublishedStatus: String? = null
    @Volatile private var playbackRequestScope = ""
    private data class PlaybackTicket(val epoch: Long, val consent: Long, val request: String,
                                      val remote: NormalVoiceStream.Ticket? = null)
    private fun listen(ticket: PlaybackTicket, valid: () -> Boolean, start: SourceServicePlaybackAdapter.ListeningEffect): Boolean {
        val stream = normalStream
        return if (normalStreaming) stream != null && stream.listen(ticket.remote, playbackRequestScope,
            consentRevision.get(), { captureValid() && ticket.consent == consentRevision.get() && valid() }, start)
        else playback.consumeListening(ticket.epoch, ticket.consent, { valid() }, start)
    }
    private fun captureValid() = running.get() && !cancelled.get() && !cancellation.blocked() && playback.controllerCurrent() &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private val playbackMutex = Any()
    @Volatile private var progressConnection: HttpURLConnection? = null
    private val deadline = Runnable {
        cancelActive(AppText.get(R.string.rt_request_timeout, "음성 요청 시간 제한이 끝났습니다."))
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** startForegroundService() obliges one startForeground() for EVERY start, including one
     * this service refuses; otherwise Android kills the whole app. A running session keeps its
     * microphone type; a refused cold start shows nothing and leaves the foreground at once. */
    private fun honourForegroundStart(startId: Int, stop: Boolean) {
        try {
            if (running.get()) {
                startForeground(NOTIFICATION, notification(getString(R.string.notif_request), status, true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION, notification(getString(R.string.notif_request), status, false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        } catch (_: RuntimeException) { /* Android refused; stopping below still applies. */ }
        if (stop) stopSelf(startId)
    }

    private var syntheticTrialStarted = false
    @Volatile private var activeSyntheticTrial: SourceSyntheticTrial? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SYNTHETIC_TRIAL) {
            if (syntheticTrialStarted || running.get() || lifecycleThread?.isAlive == true || remoteCancelWorker != null || remoteCancelLocalLease != null || destroyRemoteJoinBlocked() || playback.blocked() || cancellation.blocked())
                return START_NOT_STICKY
            syntheticTrialStarted = true
            val factory = syntheticTrialFactory
            syntheticTrialFactory = null // init-only and one use, never silent buffered fallback
            val trial = try { factory?.invoke(playback) } catch (error: Exception) {
                playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.SYNTHETIC_TRIAL, error); null
            }
            if (trial == null || !trial.owns(playback)) {
                status = AppText.get(R.string.rt_synth_rejected, "완전한 합성 시험 전송이 없어 거부했습니다")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            activeSyntheticTrial = trial
            lifecycleThread = Thread({
                try {
                    val result = trial.run()
                    syntheticTrialResult = result
                    status = AppText.get(R.string.rt_synth_done, "스트리밍 시험 완료 · 마이크 미사용")
                    android.util.Log.i("VoiceLifecycle", "stage=synthetic_complete correlation_id=${result.correlationId}")
                } catch (error: Exception) {
                    playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.SYNTHETIC_TRIAL, error)
                    playback.off()
                    status = AppText.get(R.string.rt_synth_failed, "합성 시험 실패 · 재시도/실제 음성 전환 없음")
                } finally { main.post { stopSelf(startId) } }
            }, "source-synthetic-trial").also { it.start() }
            return START_NOT_STICKY
        }
        if (syntheticTrialStarted) {
            if (intent?.action == CANCEL_REQUEST) { activeSyntheticTrial?.stop(); playback.off() }
            return START_NOT_STICKY // no microphone, config, provider or action path in synthetic mode
        }
        when (intent?.action) {
            SUBMIT_REQUEST -> {
                if (running.get()) {
                    submitRequested.set(true)
                    status = AppText.get(R.string.rt_sending, "녹음을 마치고 Hermes에 전송합니다.")
                } else {
                    status = AppText.get(R.string.rt_nothing_to_send, "전송할 음성 녹음이 없습니다.")
                }
                return START_NOT_STICKY
            }
            CANCEL_REQUEST -> {
                cancelActive(AppText.get(R.string.rt_cancelled, "음성 요청을 취소했습니다. 녹음과 재생을 중단했습니다."))
                try { CompanionUiRepository.offRequested() } catch (_: RuntimeException) { /* optional display only */ }
                stopSelf(startId)
                return START_NOT_STICKY
            }
            START_REQUEST, START_HANDSFREE -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (running.get() || lifecycleThread?.isAlive == true || remoteCancelWorker != null || remoteCancelLocalLease != null || destroyRemoteJoinBlocked() || playback.blocked() || cancellation.blocked()) {
            refuseUi(ServiceUiPublication.Blocker.OWNER_BUSY)
            VoiceDiagnostics.failed("start_busy", null,
                "running=${running.get()} thread=${lifecycleThread?.isAlive == true} blocked=${playback.blocked()}")
            Log.w("VoiceRequest", "start refused: running=${running.get()} thread=${lifecycleThread?.isAlive == true} blocked=${playback.blocked()}")
            honourForegroundStart(startId, stop = false)
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            refuseUi(ServiceUiPublication.Blocker.MIC_PERMISSION)
            VoiceDiagnostics.failed("start_mic_permission", null)
            status = AppText.get(R.string.rt_mic_needed, "마이크 권한이 필요합니다.")
            honourForegroundStart(startId, stop = true)
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("lc_config", MODE_PRIVATE)
        val token = prefs.getString("voice_token", "") ?: ""
        if (!VoiceBridgeProtocol.validToken(token)) {
            refuseUi(ServiceUiPublication.Blocker.PAIRING_MISSING)
            VoiceDiagnostics.failed("start_token", null)
            status = AppText.get(R.string.rt_pairing_needed, "음성 연결 파일을 먼저 저장하고 가져와야 합니다.")
            honourForegroundStart(startId, stop = true)
            return START_NOT_STICKY
        }
        // Stock Hermes buffers speech through its own text_to_speech tool: /status reports
        // streaming=null, so a stream can never be provisioned. Older builds left a
        // voice_streaming=true flag behind; honouring it failed every request at
        // controller_stream ("Hermes에 연결하지 못했어요") while readiness showed green.
        normalStreaming = false
        val controllerTrial = intent?.getBooleanExtra(CONTROLLER_TRIAL, false) == true
        val trialTransport = if (controllerTrial) controllerTrialFactory?.invoke(playback) else null
        if (controllerTrial && trialTransport == null) {
            refuseUi(ServiceUiPublication.Blocker.PROTOCOL_UNSUPPORTED)
            status = AppText.get(R.string.rt_test_host_unready, "시험 호스트 인증 준비가 없어 시작하지 않았습니다")
            honourForegroundStart(startId, stop = true)
            return START_NOT_STICKY // Requested trial NEVER falls back to buffered operation.
        }
        if (!MicrophoneOwner.claim(microphoneLease)) {
            refuseUi(ServiceUiPublication.Blocker.MIC_BUSY)
            status = AppText.get(R.string.rt_mic_busy, "다른 기능이 마이크를 사용 중입니다.")
            honourForegroundStart(startId, stop = true)
            return START_NOT_STICKY
        }
        if (trialTransport != null || normalStreaming) {
            playback.requireController {
                // Called inside owner gate, before asynchronous native cleanup; no IO here.
                cancelled.set(true); running.set(false); turnStopped.set(true)
                consentRevision.incrementAndGet(); playbackRequestScope = ""
                val unexpectedRevocation = !uiPublication.read().revoked
                uiPublication.off(uiScope, true)
                if (unexpectedRevocation) uiPublication.blocked(uiScope, ServiceUiPublication.Blocker.CONTROLLER_REVOKED)
            }
            if (trialTransport != null) controllerDriver = SourceControllerDriver(playback, trialTransport, SystemClock::elapsedRealtime, 3000)
        }
        if (trialTransport == null && !normalStreaming && !playback.begin(playbackEpoch.incrementAndGet(), consentRevision.incrementAndGet())) {
            MicrophoneOwner.release(microphoneLease)
            refuseUi(ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN)
            status = AppText.get(R.string.rt_playback_cleanup, "이전 재생 정리가 확인되지 않아 시작하지 않았습니다")
            honourForegroundStart(startId, stop = true)
            return START_NOT_STICKY
        }
        synchronized(cancellation) {
            activeStartId = startId
            localCleanupStartId = null
            cleanupReleasePending = false
            offStatusOwner = null
            cleanupStatusOwner = null
            offCompletionMessage = null
            offPublishedStatus = null
        }
        handsfree = intent?.action == START_HANDSFREE
        cancelled.set(false)
        turnStopped.set(false)
        submitRequested.set(false)
        lastReplyText = ""
        status = AppText.get(R.string.rt_preparing, "음성 요청을 준비하고 있습니다.")
        try {
            startForeground(NOTIFICATION, notification(getString(R.string.notif_request_ready), getString(R.string.notif_request_ready_text), true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            MicrophoneOwner.release(microphoneLease)
            refuseUi(ServiceUiPublication.Blocker.FOREGROUND_DENIED)
            status = AppText.get(R.string.rt_fg_mic_denied, "Android가 전경 마이크 서비스를 시작하지 못했습니다.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running.set(true)
        main.removeCallbacks(deadline)
        handsfreeActive = handsfree
        synchronized(cancellation) {
            // Existing accepted service startId is the display generation, not a new permit.
            uiScope = uiPublication.start(startId, if (handsfree) ServiceUiPublication.Mode.HANDSFREE else ServiceUiPublication.Mode.MANUAL)
            uiRemoteJoined = true
            uiChannel = uiPublication
        }
        playback.bindUiPublication(uiPublication, uiScope) // never take playback under cancellation
        main.post(widgetTick)
        if (handsfree) {
            wakeLock = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voiceWake").apply { acquire() }
            lifecycleThread = Thread({ withController(startId, token) { handsfreeLoop(startId, token) } }, "voice-handsfree").also { it.start() }
        } else {
            main.postDelayed(deadline, REQUEST_LIMIT_MS)
            lifecycleThread = Thread({ withController(startId, token) { captureAndSend(startId, token) } }, "voice-request-capture").also { it.start() }
        }
        return START_NOT_STICKY
    }

    /**
     * Stable per-install identity. A fresh UUID per process looks like a device
     * swap to the server, which then refuses /connect with 423. Persist it once
     * and reuse it; only a reinstall (prefs cleared) creates a new identity.
     */
    private fun streamDeviceEpoch(): String {
        val prefs = getSharedPreferences("handsfree", MODE_PRIVATE)
        prefs.getString("stream_device_epoch", null)?.let { if (it.isNotBlank()) return it }
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("stream_device_epoch", created).apply()
        return created
    }

    private fun withController(startId: Int, token: String, work: () -> Unit) {
        val displayScope = uiScope
        var displayFailure = ServiceUiPublication.Blocker.HOST_UNAVAILABLE
        uiPublication.phase(displayScope, ServiceUiPublication.Phase.PREPARING)
        var stage = "host_route_contract"
        try {
            // No microphone capture, decoder load or provider dispatch before this gate.
            VoiceBridgeClient.requireVoiceExecution(token)
            displayFailure = ServiceUiPublication.Blocker.HOST_UNAVAILABLE
            stage = "controller_stream"
            if (normalStreaming) {
                val provision = VoiceBridgeClient.streamingStatus(token)
                displayFailure = ServiceUiPublication.Blocker.PROTOCOL_UNSUPPORTED
                stage = "controller_protocol"
                check(provision.getInt("protocol") == 1 && provision.getString("profile") == "voicelink")
                val transport = NormalVoiceStream(provision.getInt("port"), token, streamDeviceEpoch(), playback)
                normalStream = transport
                controllerDriver = SourceControllerDriver(playback, transport, SystemClock::elapsedRealtime, 3000)
            }
            controllerDriver?.let { driver ->
                displayFailure = ServiceUiPublication.Blocker.HOST_UNAVAILABLE
                stage = "controller_prepare"
                check(driver.prepare()) { "controller_unavailable" }
                stage = "controller_capture"
                check(captureValid()) { "controller_unavailable" }
                stage = "controller_begin"
                check(playback.beginChecked(playbackEpoch.incrementAndGet(), consentRevision.incrementAndGet(),
                    { captureValid() })) { "controller_revoked" }
            }
            stage = "controller_work"
            displayFailure = ServiceUiPublication.Blocker.REQUEST_FAILED
            work()
        } catch (error: Exception) {
            uiPublication.blocked(displayScope,
                if (error is VoiceBridgeClient.BridgeException && error.category == "HOST_ROUTE_CONTRACT_UNAVAILABLE")
                    ServiceUiPublication.Blocker.MODEL_ROUTE_INVALID else displayFailure)
            VoiceDiagnostics.failed(stage, error)
            VoiceDiagnostics.reportException(stage, error)
            // Stage, exception type, and the transport failure code only: no token, no screen text, no speech.
            Log.w("VoiceRequest", "controller failed stage=${VoiceDiagnosticJournal.stage(stage)} type=${VoiceDiagnosticJournal.type(error)}")
            playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.CONTROLLER, error)
            status = if (stage == "host_route_contract") AppText.get(R.string.rt_route_unsupported, "호스트가 안전한 음성 모델 적용을 지원하지 않아 마이크를 시작하지 않았습니다.")
                else AppText.get(R.string.rt_connect_failed, "연결 실패 · ${VoiceDiagnosticJournal.stage(stage)} · ${VoiceDiagnosticJournal.type(error)}", VoiceDiagnosticJournal.stage(stage), VoiceDiagnosticJournal.type(error))
            finish(startId)
        }
    }

    private fun handsfreeLoop(startId: Int, token: String) {
        val displayScope = uiScope
        var pendingDuplexPcm: RetainedPcm? = null
        try {
            status = AppText.get(R.string.rt_checking_local, "Hermes 로컬 연결 확인 중")
            check(VoiceBridgeClient.ready(token)) { "bridge_not_ready" }
            status = AppText.get(R.string.rt_wake_model, "로컬 호출어 모델 준비 중")
            var rejectedNotice: String? = null
            HandsfreeCapture(applicationContext, { captureValid() },
                { message ->
                    // Retain rejection feedback while returning to wake wait; the next
                    // actual capture replaces it. Otherwise the immediate loop hides it.
                    val visible = if (message == AppText.wakeWait() && rejectedNotice != null)
                        "$rejectedNotice · $message" else message.also { rejectedNotice = null }
                    status = visible
                    main.post {
                    if (running.get()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                        notification(getString(R.string.notif_listening), visible, false))
                } }, { recorder = it }, { outcome, primary -> onCaptureCleanup(outcome, primary, displayScope) },
                { event -> uiPublication.captureEvent(displayScope, event) }).use { capture ->
                var followup: PlaybackTicket? = null
                while (running.get() && !cancelled.get()) {
                    val ticket = followup
                    val epoch = ticket?.epoch ?: playbackEpoch.get()
                    val revision = ticket?.consent ?: consentRevision.get()
                    val retained = pendingDuplexPcm
                    pendingDuplexPcm = null
                    val pcm = if (retained != null) retained.pcm else try {
                        capture.nextRequest(ticket != null) { start ->
                            if (ticket != null) listen(ticket,
                                { captureValid() && !turnStopped.get() && ticket.request == playbackRequestScope && revision == consentRevision.get() }, start)
                            else playback.captureIdle(epoch, revision, { captureValid() }, start)
                        }
                    } catch (rejected: SpeechEndGate.Rejected) {
                        if (!capture.cleanupConfirmed) throw rejected
                        status = captureRejectionStatus(rejected)
                        rejectedNotice = status
                        ByteArray(0) // No upload, acceptance, STOP, or retained follow-up ticket.
                    } finally { playback.endListening(epoch, capture.cleanupConfirmed) }
                    followup = null
                    if (pcm == null) break
                    if (capture.wasStopped) {
                        McpService.actionsPaused = true
                        stopConversation(token)
                        check(playback.beginChecked(playbackEpoch.incrementAndGet(), consentRevision.get(),
                            { captureValid() })) { "playback_owner_blocked" }
                        status = AppText.get(R.string.rt_conversation_end, "대화 종료 · 호출어 대기로 돌아갑니다")
                        continue
                    }
                    if (pcm.isEmpty()) continue
                    if (!running.get()) { pcm.fill(0); break }
                    turnStopped.set(false)
                    McpService.actionsPaused = false
                    requestAbsoluteDeadline = SystemClock.elapsedRealtime() + REQUEST_LIMIT_MS
                    main.post { if (running.get()) main.postDelayed(deadline, REQUEST_LIMIT_MS) }
                    val done = AtomicBoolean(false)
                    val duplexRevision = consentRevision.get()
                    val duplexTurn = newDuplexTurn(token, duplexRevision)
                    val success = java.util.concurrent.atomic.AtomicReference<PlaybackTicket?>(null)
                    val request = Thread({
                        try { promoteToPlayback(); success.set(submitAndSpeak(pcm, token, retained)) }
                        catch (error: Exception) {
                            playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.REQUEST, error)
                            uiPublication.blocked(uiPublication.requestScope(), ServiceUiPublication.Blocker.REQUEST_FAILED)
                            if (running.get() && !turnStopped.get()) status = AppText.get(R.string.rt_request_failed_retry, "요청 실패: ${safeCategory(error)} · 다시 호출해 주세요", safeCategory(error))
                        }
                        finally {
                            // Content-done releases the monitor loop. It is NOT a played ticket.
                            // Keep exact accepted cancellation and the absolute timer until settlement.
                            val abandoned = if (normalStreaming && pendingFinalContent != null) null else
                                synchronized(cancellation) {
                                    val displayRequest = uiPublication.requestScope()
                                    cancellation.cancel().also {
                                        activeRequestId = ""
                                        if (it != null) uiPublication.retireRequest(displayRequest, false)
                                    }
                                }
                            try { if (abandoned != null) cancelRemote(token, abandoned) }
                            finally { ActionConfirmation.cancelAll(); pcm.fill(0); done.set(true) }
                        }
                    }, "handsfree-turn")
                    request.start()
                    var turnFailure: Throwable? = null
                    try {
                        while (!done.get() && running.get() && !turnStopped.get()) {
                            var localStop = false
                            capture.monitorStop({ !done.get() && ActionConfirmation.voicePending()==null },
                                { start -> playback.startMonitor({ captureValid() && !done.get() && !turnStopped.get() }, start) },
                                { duplexMonitorReference() },
                                { playback.legacyStopAllowed() }, duplexTurn) { localStop = true }
                            if (localStop) stopConversation(token) // monitor has closed its one AudioRecord
                            val approval=ActionConfirmation.voicePending()
                            if(approval != null && !done.get() && !turnStopped.get()) handleVoiceConfirmation(capture,token,approval)
                        }
                    } catch (error: Throwable) {
                        turnFailure = error
                    } finally {
                        pendingDuplexPcm = settleHandsfreeTurn(token, capture.cleanupConfirmed,
                            request, duplexTurn, duplexRevision, success, turnFailure)
                    }
                    if (running.get() && pendingDuplexPcm == null) Thread.sleep(800)
                    followup = success.get()?.takeIf { !turnStopped.get() }
                    if (turnStopped.get() && running.get() && pendingDuplexPcm == null) {
                        check(quiescePlayback(true)) { "playback_cleanup_unconfirmed" }
                        check(playback.beginChecked(playbackEpoch.incrementAndGet(), consentRevision.get(), { captureValid() })) { "playback_owner_blocked" }
                    }
                }
            }
        } catch (error: Exception) {
            playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.HANDSFREE, error)
            uiPublication.blocked(displayScope, ServiceUiPublication.Blocker.REQUEST_FAILED)
            if (running.get()) status = AppText.get(R.string.rt_listen_error, "듣기 오류: ${safeCategory(error)}", safeCategory(error))
        } finally {
            try { pendingDuplexPcm?.pcm?.fill(0) } finally { finish(startId) }
        }
    }

    /** Capture has exited its native finally before this lifecycle-only settlement.
     * Independent cleanup attempts must not be skipped by an earlier failed assertion.
     * Neither OFF nor an erased candidate is evidence of failed old-request cleanup. */
    private fun settleHandsfreeTurn(token: String, captureClean: Boolean, request: Thread,
                                   duplexTurn: DuplexTurnHandoff, revision: Long,
                                   success: java.util.concurrent.atomic.AtomicReference<PlaybackTicket?>,
                                   primary: Throwable?): RetainedPcm? {
        var first = primary
        var interrupted = Thread.interrupted()
        fun settle(step: () -> Unit) {
            try { step() } catch (error: Throwable) {
                if (first == null) first = error else if (first !== error) first!!.addSuppressed(error)
                if (Thread.interrupted() || error is InterruptedException) interrupted = true
                playback.failCleanup()
            }
        }
        var retained: RetainedPcm? = null
        try {
            if (!running.get()) {
                settle { connection?.disconnect() }
                settle { playback.off() }
            }
            settle { check(captureClean) { "capture_cleanup_unconfirmed" } }
            settle { request.join(130000); check(!request.isAlive) { "request_cleanup_timeout" } }
            settle { check(playback.stopProgressAndJoin(5000)) { "progress_cleanup_unconfirmed" } }
            settle { check(duplexTurn.awaitCleanup(25000)) { "duplex_cleanup_unconfirmed" } }
            // Do not race an unjoined request mutating its exact renderer. Its timeout is
            // already sticky; retain the handle instead of inventing a close certificate.
            if (!request.isAlive && normalStreaming && requestRender != null) {
                settle { success.set(settleRequestRender()) }
            }
            // settleRequestRender initiates maintenance closure before the physical join.
            settle { check(playback.awaitWorker(5000)) { "duplex_render_cleanup_unconfirmed" } }
            settle {
                val abandoned = synchronized(cancellation) {
                                    val displayRequest = uiPublication.requestScope()
                                    cancellation.cancel().also {
                                        activeRequestId = ""
                                        if (it != null) uiPublication.retireRequest(displayRequest, false)
                                    }
                                }
                if (abandoned != null) check(cancelRemote(token, abandoned)) { "cancel_cleanup_unconfirmed" }
            }
            settle { check(joinRemoteCancellation()) { "cancel_worker_cleanup_unconfirmed" } }
            if (!running.get() || first != null) {
                settle { check(controllerDriver?.stopAndJoin(3000) ?: true) { "controller_cleanup_unconfirmed" } }
            }
            settle { check(!playback.blocked() && !cancellation.blocked()) { "owner_cleanup_unconfirmed" } }
            if (first == null && captureClean && !request.isAlive && duplexTurn.scope() != null && running.get()) {
                // takeDuplexPcm retains the original owner/current/qualification gates.
                settle { retained = takeDuplexPcm(duplexTurn, revision) }
            }
        } finally {
            settle { duplexTurn.close() }
            settle { main.removeCallbacks(deadline) }
            if (interrupted) Thread.currentThread().interrupt()
        }
        first?.let { retained?.pcm?.fill(0); throw it }
        return retained
    }

    /** Capture invokes only the owner-gated logical prepare. All disconnect/abort/join/HTTP
     * work belongs to the one tracked DuplexTurnHandoff worker, never AudioRecord.read. */
    private fun newDuplexTurn(token: String, revision: Long): DuplexTurnHandoff {
        val displaySession = uiScope
        fun current(scope: PlaybackReference.Scope) = playback.duplexCurrent(scope, revision) {
            running.get() && !cancelled.get() && revision == consentRevision.get() &&
                playbackRequestScope == scope.request && playbackEpoch.get() == scope.generation &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        return DuplexTurnHandoff({ expected ->
            var target: ScopedVoiceCancellation.Scope? = null
            val retained = playback.retainDuplex(expected, revision,
                { captureValid() && !turnStopped.get() && playbackRequestScope == expected.request }) {
                synchronized(cancellation) {
                    target = cancellation.cancelRequest(expected.request)
                    check(target != null) { "duplex_cancel_scope_missing" }
                    turnStopped.set(true); activeRequestId = ""
                    uiPublication.retireRequest(uiPublication.requestScope(), false)
                }
            }
            val exact = target
            if (!retained || exact == null) null else DuplexTurnHandoff.Cleanup {
                var confirmed = false
                try {
                    // Cancel the captured OLD request even if OFF wins or source.close stalls.
                    // NormalVoiceStream owns /normal/cancel; its bounded source teardown is
                    // independent of this worker and the same-track maintenance renderer.
                    ActionConfirmation.cancelAll(); McpService.actionsPaused = true
                    val remoteClean = VoiceBridgeClient.cancelAndAwaitCleanup(token, exact)
                    confirmed = remoteClean
                    confirmed
                } catch (error: Exception) {
                    playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.REQUEST, error)
                    false
                } finally {
                    synchronized(cancellation) {
                        cancellation.acknowledge(exact, confirmed)
                        uiPublication.remote(ServiceUiPublication.Scope(displaySession.generation, exact.ingressOperationId),
                            if (confirmed) ServiceUiPublication.Cleanup.CONFIRMED else ServiceUiPublication.Cleanup.UNCERTAIN, uiRemoteJoined)
                    }
                    if (!confirmed) playback.failCleanup()
                    settleOffCleanup(displaySession)
                }
            }
        }, { scope -> current(scope) })
    }

    private fun duplexMonitorReference() = playback.monitorReference(playbackEpoch.get())

    /** Bounded in-memory transfer only; caller already joined capture/request/cancel.
     * The ordinary loop passes the result to existing submitAndSpeak without recapture. */
    private class RetainedPcm(val pcm: ByteArray, val permit: SourceServicePlaybackAdapter.UploadPermit) {
        val attempted = AtomicBoolean(false)
    }

    private fun takeDuplexPcm(turn: DuplexTurnHandoff, revision: Long): RetainedPcm? {
        var pcm: RetainedPcm? = null
        val scope = turn.scope() ?: return null
        playback.finishDuplex(scope, revision, { captureValid() && turnStopped.get() }) {
            turn.take()?.use { command ->
                // Move the existing clean-turn owner transition into this same transfer
                // gate. Leaving it in the loop tail would revoke the captured upload.
                val epoch = playbackEpoch.incrementAndGet()
                check(playback.beginChecked(epoch, revision) { captureValid() }) { "playback_owner_blocked" }
                val permit = playback.reserveUpload(epoch, revision) { captureValid() && turnStopped.get() }
                    ?: return@use
                val bytes = ByteArray(command.pcm.size * 2).also { bytes ->
                    command.pcm.forEachIndexed { i, sample ->
                        bytes[i*2] = sample.toByte(); bytes[i*2+1] = (sample.toInt() ushr 8).toByte()
                    }
                }
                pcm = RetainedPcm(bytes, permit)
                uiPublication.phase(uiScope, ServiceUiPublication.Phase.SUBMITTING)
            }
        }
        return pcm
    }

    private fun captureRejectionStatus(rejected: SpeechEndGate.Rejected): String = when (rejected.reason) {
        SpeechEndGate.Decision.SAFETY_LIMIT -> AppText.get(R.string.rt_limit_reached, "요청이 30초 녹음 제한에 도달해 전체 녹음을 전송하지 않았습니다. 나누어 다시 말해 주세요.")
        else -> AppText.get(R.string.rt_end_unconfirmed, "발화 종료를 확인하지 못해 전송하지 않았습니다. 다시 말해 주세요.")
    }

    private fun captureAndSend(startId: Int, token: String) {
        val displayScope = uiScope
        var audio: AudioRecord? = null
        var pcm: ByteArray? = null
        var failure = ""
        var primary: Throwable? = null
        val captured = WipingByteArrayOutputStream(32 * 1024)
        var sampleBuffer = ShortArray(0)
        val started = SystemClock.elapsedRealtime()
        try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "audio_buffer_unavailable" }
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 16000))
            audio = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "audio_recorder_unavailable" }
            recorder = record
            check(playback.captureIdle(playbackEpoch.get(), consentRevision.get(), { captureValid() }) {
                record.startRecording()
            }) { "capture_owner_revoked" }
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "audio_start_failed" }
            uiPublication.captureEvent(displayScope, ServiceUiPublication.CaptureEvent.CAPTURING)
            status = AppText.get(R.string.rt_recording_limit, "녹음 중 · 30초 전에 전송해 주세요 · 제한 도달 시 전체 녹음을 폐기합니다.")
            sampleBuffer = ShortArray(1600)
            val captureDeadline = started + CAPTURE_LIMIT_MS
            while (captureValid() && !submitRequested.get()
                && SystemClock.elapsedRealtime() < captureDeadline) {
                val count = record.read(sampleBuffer, 0, sampleBuffer.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "audio_read_failed" }
                if (count > 0) {
                    // A fast PCM source can hit the sample cap before wall time.
                    // Neither bound is consent to send a clipped command, even if SUBMIT races it.
                    if (captured.size().toLong() + count * 2L >= SAMPLE_RATE * 2L * CAPTURE_LIMIT_MS / 1000 ||
                        captured.size().toLong() + count * 2L > VoiceBridgeProtocol.MAX_HTTP_BODY - 44) {
                        throw SpeechEndGate.Rejected(SpeechEndGate.Decision.SAFETY_LIMIT)
                    }
                    for (index in 0 until count) {
                        val sample = sampleBuffer[index].toInt()
                        captured.write(sample and 0xff)
                        captured.write((sample ushr 8) and 0xff)
                    }
                    sampleBuffer.fill(0)
                    status = AppText.get(R.string.rt_recording_secs, "녹음 중 · ${captured.size() / (SAMPLE_RATE * 2)}초 · 전송 버튼으로 마칩니다.", captured.size() / (SAMPLE_RATE * 2))
                } else {
                    Thread.sleep(20)
                }
            }
            if (running.get() && !cancelled.get()) {
                if (SystemClock.elapsedRealtime() >= captureDeadline) {
                    throw SpeechEndGate.Rejected(SpeechEndGate.Decision.SAFETY_LIMIT)
                }
                if (submitRequested.get()) pcm = captured.takeAndWipe()
            }
        } catch (error: Throwable) {
            primary = error
            if (running.get() && !cancelled.get()) failure = error.javaClass.simpleName
        } finally {
            val outcome = CaptureCleanup.release(
                { audio?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } },
                null, { audio?.release() })
            onCaptureCleanup(outcome, primary, displayScope)
            playback.endListening(playbackEpoch.get(), outcome.confirmed())
            primary = outcome.preserve(primary)
            if (!outcome.confirmed()) failure = "capture_cleanup_unconfirmed"
            if (outcome.confirmed()) recorder = null
            captured.wipe()
            sampleBuffer.fill(0)
        }
        if (primary != null && primary !is Exception) {
            pcm?.fill(0)
            finish(startId)
            throw primary
        }

        if (!running.get() || cancelled.get()) {
            pcm?.let { Arrays.fill(it, 0) }
            finish(startId)
            return
        }
        if (failure.isNotEmpty()) {
            pcm?.fill(0)
            status = if (primary is SpeechEndGate.Rejected && failure != "capture_cleanup_unconfirmed")
                captureRejectionStatus(primary)
            else AppText.get(R.string.rt_record_failed, "녹음을 시작하거나 읽지 못했습니다: $failure", failure)
            finish(startId)
            return
        }
        if (pcm == null || pcm!!.size < MIN_PCM_BYTES) {
            pcm?.let { Arrays.fill(it, 0) }
            status = AppText.get(R.string.rt_too_short, "녹음이 너무 짧아 전송하지 않았습니다.")
            finish(startId)
            return
        }

        try {
            promoteToPlayback()
            submitAndSpeak(pcm!!, token)
            if (normalStreaming && requestRender != null) settleRequestRender()
        } catch (error: Exception) {
            playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.REQUEST, error)
            VoiceDiagnostics.reportException("voice_request", error)
            uiPublication.blocked(displayScope, ServiceUiPublication.Blocker.REQUEST_FAILED)
            if (running.get() && !cancelled.get()) status = AppText.get(R.string.rt_voice_request_failed, "음성 요청 실패: ${safeCategory(error)}", safeCategory(error))
        } finally {
            Arrays.fill(pcm!!, 0)
            connection?.disconnect()
            connection = null
            finish(startId)
        }
    }

    private fun submitAndSpeak(pcm: ByteArray, token: String, retained: RetainedPcm? = null): PlaybackTicket? {
        val displaySession = uiScope
        var displayRequest: ServiceUiPublication.Scope? = null
        // Preserve capture ownership through unlock/session lookup/WAV conversion. Never
        // re-mint permission from a post-transfer snapshot or replay an uncertain upload.
        if (retained != null && (retained.pcm !== pcm || !retained.attempted.compareAndSet(false, true))) return null
        if (!handsfree) requestAbsoluteDeadline = SystemClock.elapsedRealtime() + REQUEST_LIMIT_MS
        check(requestAbsoluteDeadline > SystemClock.elapsedRealtime()) { "request_deadline_expired" }
        pendingFinalContent = null
        val epoch = playbackEpoch.get()
        val revision = consentRevision.get()
        val permit = retained?.permit ?: playback.reserveUpload(epoch, revision) { captureValid() && !turnStopped.get() }
            ?: return null
        uiPublication.waitFor(displaySession, ServiceUiPublication.Wait.UNLOCK)
        status = AppText.get(R.string.rt_lock_check, "잠금 확인 · 필요하면 화면에서 직접 인증해 주세요")
        val unlocked = try {
            UnlockCoordinator.await(this) { running.get() && !cancelled.get() && !turnStopped.get() }
        } finally {
            uiPublication.waitFor(displaySession, ServiceUiPublication.Wait.NONE)
        }
        if (!unlocked) {
            status = AppText.get(R.string.rt_unlock_cancelled, "잠금 해제 취소 또는 시간 초과 · 요청을 전송하지 않았습니다")
            return null
        }
        val generation = VoiceBridgeClient.cancellationSession(token)
        val uploadScope = synchronized(cancellation) {
            if (!running.get() || cancelled.get() || turnStopped.get()) return null
            cancellation.begin(generation, java.util.UUID.randomUUID().toString()).also {
                displayRequest = uiPublication.beginRequest(displaySession, it.ingressOperationId)
            }
        }
        var acceptedScope: ScopedVoiceCancellation.Scope? = null
        val wav = VoiceBridgeProtocol.toWav(pcm)
        val requestId: String
        try {
            status = AppText.get(R.string.rt_stt_sending, "음성을 Hermes STT로 전송합니다.")
            VoiceDiagnostics.uploads.incrementAndGet()
            val accepted = VoiceBridgeClient.submitAudio(token, wav, uploadScope, beforeUpload = {
                check(playback.consumeUpload(permit, {
                    captureValid() && !turnStopped.get() && epoch == playbackEpoch.get() && revision == consentRevision.get()
                }) {
                    synchronized(cancellation) {
                        check(cancellation.current(uploadScope)) { "upload_scope_revoked" }
                    }
                }) { "upload_owner_revoked" }
            }) { connection = it }
            val noExecution = VoiceSttOutcome.accepted(accepted)
            if (noExecution != null) {
                synchronized(cancellation) {
                    cancellation.complete(uploadScope)
                    uiPublication.retireRequest(displayRequest, true)
                }
                if (!turnStopped.get() && !cancelled.get()) status = noExecution
                return null
            }
            if (!accepted.optBoolean("accepted")) throw VoiceBridgeClient.BridgeException("voice_not_accepted")
            requestId = accepted.optString("request_id")
            synchronized(cancellation) {
                acceptedScope = cancellation.accept(uploadScope, accepted.optString("session_generation"),
                    accepted.optString("ingress_operation_id"), requestId)
                if (acceptedScope == null || !running.get() || cancelled.get() || turnStopped.get()) return null
                activeRequestId = requestId
                playbackRequestScope = requestId
                uiPublication.acceptRequest(displayRequest, requestId)
            }
            if (normalStreaming) {
                acceptedRenderCancellation = acceptedScope
                requestRender = (normalStream ?: throw IOException("stream_unavailable")).beginRequest(
                    acceptedScope, playbackEpoch.incrementAndGet(), revision, requestAbsoluteDeadline,
                    { captureValid() && !turnStopped.get() && playbackRequestScope == requestId &&
                        cancellation.current(acceptedScope) && revision == consentRevision.get() }, StreamingAudioTrackSink.clock())
            }
            playback.requestStarted() // Local numeric correlation only; never log remote IDs/text/tokens.
        } finally {
            Arrays.fill(wav, 0)
        }

        status = AppText.get(R.string.rt_waiting_reply, "현재 Hermes 대화에서 답변을 기다립니다.")
        val replyDeadline = minOf(requestAbsoluteDeadline, SystemClock.elapsedRealtime() + REPLY_WAIT_MS)
        var replyText: String? = null
        val events = VoiceEventQueue(requestId)
        try {
        while (running.get() && !cancelled.get() && !turnStopped.get() && SystemClock.elapsedRealtime() < replyDeadline) {
            val response = VoiceBridgeClient.pollReply(token, requestId, events.cursor()) { connection = it }
            if (response.optBoolean("ready")) {
                val item = response.optJSONObject("item") ?: throw VoiceBridgeClient.BridgeException("reply_invalid")
                if (item.optString("request_id") != requestId || item.has("progress_owner"))
                    throw VoiceBridgeClient.BridgeException("reply_owner_mismatch")
                replyText = item.optString("text").take(4000)
                break
            }
            val event=response.optJSONObject("event")
            if (response.has("progress_seq") && response.optLong("progress_seq") == 0L) {
                events.invalidate()
                progressConnection?.disconnect()
                check(playback.stopProgressAndJoin(3000)) { "progress_cleanup_unconfirmed" }
            }
            if(event!=null && ActionConfirmation.voicePending()==null) {
                val owner=event.optString("request_id");val seq=event.optLong("seq")
                // Admit the latest identity BEFORE joining the previous producer. A busy
                // player must not hold its old event current while newer stages arrive.
                if(events.accept(owner,seq)) {
                    progressConnection?.disconnect()
                    check(playback.stopProgressAndJoin(3000)) { "progress_cleanup_unconfirmed" }
                    check(playback.startProgress {
                        fun active() = events.active(owner,seq) && activeRequestId==owner && running.get() &&
                            !cancelled.get() && !turnStopped.get() && ActionConfirmation.voicePending()==null
                        try {
                            if (active()) speakReplyParts(token,event.getString("audio_id"), { active() }, true) { progressConnection=it }
                        } catch (error: Exception) {
                            if (active()) {
                                playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.PROGRESS, error)
                                VoiceDiagnostics.reportException("progress_play", error)
                            }
                            // Supersession is not success, permission, or a new paid retry.
                        }
                        finally { progressConnection=null }
                    }) { "progress_start_rejected" }
                }
            }
            Thread.sleep(500)
        }
        } finally {
            events.finish()
            progressConnection?.disconnect()
            check(playback.stopProgressAndJoin(3000)) { "progress_cleanup_unconfirmed" }
        }
        if (!running.get() || cancelled.get() || turnStopped.get()) return null
        if (replyText.isNullOrBlank()) throw VoiceBridgeClient.BridgeException("reply_timeout")
        synchronized(cancellation) {
            if (cancellation.current(acceptedScope) && activeRequestId == requestId && running.get() &&
                !cancelled.get() && !turnStopped.get()) uiPublication.publishFinal(displayRequest, requestId, replyText)
        }
        lastReplyText = replyText!!
        status = AppText.get(R.string.rt_reply_received, "Hermes 답변을 받았습니다. 설정된 음성을 준비하고 있습니다.")

        val ticket = speakReplyParts(token, requestId) { connection = it }
        if (ticket != null && !normalStreaming) synchronized(cancellation) {
            cancellation.complete(acceptedScope)
            uiPublication.retireRequest(displayRequest, true)
        }
        return ticket
    }

    private fun settleRequestRender(): PlaybackTicket? {
        val render = requestRender ?: return null
        var first: Exception? = null
        try { check(joinRemoteCancellation()) { "cancel_worker_cleanup_unconfirmed" } }
        catch (error: Exception) { first = error; playback.failCleanup() }
        val interrupted = Thread.interrupted() || first is InterruptedException
        // A remote join failure cannot skip the independent exact source/sink closure.
        // Poison above prevents this cleanup from granting a played receipt/ticket.
        val remote = try {
            (normalStream ?: throw IOException("stream_unavailable")).settle(render, pendingFinalContent)
        } catch (error: Exception) {
            val primary = first
            if (primary != null) { if (primary !== error) primary.addSuppressed(error); throw primary }
            throw error
        } finally { if (interrupted) Thread.currentThread().interrupt() }
        requestRender = null
        pendingFinalContent = null
        first?.let { throw it }
        if (remote == null) return null
        synchronized(cancellation) {
            cancellation.complete(acceptedRenderCancellation)
            acceptedRenderCancellation?.let { uiPublication.retireRequest(ServiceUiPublication.Scope(uiScope.generation, it.ingressOperationId), true) }
        }
        acceptedRenderCancellation = null
        return PlaybackTicket(remote.generation, remote.consent, playbackRequestScope, remote)
    }

    private fun speechPlaybackSpeed(): Float = try {
        SpeechPlaybackPolicy.storedSpeed(getSharedPreferences("handsfree", MODE_PRIVATE)
            .getFloat(SpeechPlaybackPolicy.PREFERENCE, SpeechPlaybackPolicy.DEFAULT_SPEED))
    } catch (_: ClassCastException) { SpeechPlaybackPolicy.DEFAULT_SPEED }

    private fun speakReplyParts(token: String, audioId: String,
                                stillWanted: () -> Boolean = { true }, progress: Boolean = false, approval: Boolean = false,
                                onConnection: (HttpURLConnection?) -> Unit): PlaybackTicket? = synchronized(playbackMutex) {
        if (normalStreaming) {
            val stream = normalStream ?: throw IOException("stream_unavailable")
            check(!approval) { "request_render_approval_unsupported" }
            val render = requestRender ?: throw IOException("request_render_missing")
            val request = playbackRequestScope
            val wireGeneration = contentEpoch.incrementAndGet()
            check(wireGeneration > 0) { "content_generation_exhausted" }
            val content = stream.playContent(render, audioId, wireGeneration,
                if (progress) SourceServicePlaybackAdapter.Kind.PROGRESS else SourceServicePlaybackAdapter.Kind.FINAL,
                { captureValid() && !turnStopped.get() && request == playbackRequestScope && stillWanted() },
                { StreamingAudioTrackSink(speechPlaybackSpeed()) })
            if (content.finalContent) pendingFinalContent = content
            // Return to the request thread, allowing monitorStop's actual finally to run.
            // No ticket is created until settleRequestRender joins physical cleanup.
            return@synchronized null
        }
        var lastEpoch: Long? = null
        if (!captureValid() || turnStopped.get() || !stillWanted()) return@synchronized null
        check(playback.beginPlaybackChecked(playbackEpoch.incrementAndGet(), consentRevision.get(),
            { captureValid() && !turnStopped.get() && stillWanted() })) { "playback_owner_blocked" }
        val sequence = ReplyAudioSequence()
        val deadline = SystemClock.elapsedRealtime() + TTS_WAIT_MS + 5 * 60_000L
        fun active() = running.get() && !cancelled.get() && !turnStopped.get() && stillWanted() &&
            SystemClock.elapsedRealtime() < deadline
        try {
            while (active() && !sequence.complete()) {
                var audio: ByteArray? = null
                try {
                    val waitDeadline = minOf(deadline, SystemClock.elapsedRealtime() + TTS_WAIT_MS)
                    var result: VoiceBridgeClient.TtsResult? = null
                    while (active() && SystemClock.elapsedRealtime() < waitDeadline) {
                        val fetched = VoiceBridgeClient.fetchTts(token, audioId, sequence.next(), onConnection)
                        if (!fetched.pending) { result = fetched; audio = fetched.bytes; break }
                        Thread.sleep(500)
                    }
                    if (!active()) return@synchronized null
                    val part = result ?: throw VoiceBridgeClient.BridgeException("tts_timeout")
                    val bytes = audio ?: throw VoiceBridgeClient.BridgeException("tts_audio_empty")
                    sequence.begin(part.segment, part.segmentCount, bytes.size)
                    lastEpoch = playAudio(bytes, { active() }, progress, approval)
                    if (!active()) return@synchronized null
                    sequence.played()
                } finally { audio?.fill(0) }
            }
            return@synchronized if (sequence.complete() && active() && lastEpoch != null)
                PlaybackTicket(lastEpoch!!, consentRevision.get(), playbackRequestScope) else null
        } finally { sequence.cancel() }
    }

    private fun handleVoiceConfirmation(capture: HandsfreeCapture, token: String, pending: ActionConfirmation.Pending) {
        val displayScope = uiPublication.requestScope()
        uiPublication.waitFor(displayScope, ServiceUiPublication.Wait.APPROVAL)
        if (normalStreaming) {
            // No silent v1 boundary or fresh APM for approval inside a request renderer.
            ActionConfirmation.cancelAll(); turnStopped.set(true)
            uiPublication.blocked(displayScope, ServiceUiPublication.Blocker.PROTOCOL_UNSUPPORTED)
            throw IOException("request_render_approval_unsupported")
        }
        fun active() = running.get() && !cancelled.get() && !turnStopped.get() && pending.result==null && activeRequestId==pending.voiceRequest
        progressConnection?.disconnect()
        check(playback.stopProgressAndJoin(3000)) { "progress_cleanup_unconfirmed" }
        synchronized(playbackMutex) {
        try {
            var event=JSONObject()
            val registerDeadline=SystemClock.elapsedRealtime()+5000
            while(active() && SystemClock.elapsedRealtime()<registerDeadline) {
                event=VoiceBridgeClient.confirmation(token,pending.voiceRequest,pending.id)
                if(event.optBoolean("ok")) break
                Thread.sleep(100)
            }
            check(active() && event.optBoolean("ok") && event.optString("digest")==pending.digest) { "confirmation_unavailable" }
            VoiceDiagnostics.approvals.incrementAndGet()
            status=AppText.get(R.string.rt_confirm_voice, "작업 내용을 음성으로 확인합니다")
            val ticket = speakReplyParts(token,event.getString("audio_id"), { active() }, approval = true) { connection=it }
                ?: throw VoiceBridgeClient.BridgeException("confirmation_tts_failed")
            if(!active()) return
            // Open a new microphone only after successful playback and an acoustic tail gap.
            Thread.sleep(500)
            val armed=VoiceBridgeClient.armConfirmation(token,pending.voiceRequest,pending.id)
            check(active() && armed.optString("digest")==pending.digest) { "confirmation_cancelled" }
            status=AppText.get(R.string.rt_say_allow, "허용 문구를 말씀해 주세요 · 다른 응답에는 실행하지 않습니다")
            val pcm = try {
                capture.nextRequest(true) { start -> listen(ticket,
                    { captureValid() && active() && ticket.request == playbackRequestScope && ticket.consent == consentRevision.get() }, start) }
            } catch (rejected: SpeechEndGate.Rejected) {
                if (!capture.cleanupConfirmed) throw rejected
                status = captureRejectionStatus(rejected)
                ByteArray(0) // Empty approval audio cancels, never authorizes the pending effect.
            } finally { playback.endListening(ticket.epoch, capture.cleanupConfirmed) } ?: ByteArray(0)
            try {
                if(capture.wasStopped) { stopConversation(token); return }
                if(!active() || pcm.isEmpty()) { ActionConfirmation.cancelAll(); return }
                VoiceDiagnostics.approvalCaptures.incrementAndGet()
                val wav=VoiceBridgeProtocol.toWav(pcm)
                try {
                    val response=VoiceBridgeClient.confirmationAudio(token,pending.voiceRequest,pending.id,armed.getString("nonce"),wav) { connection=it }
                    if(active()) ActionConfirmation.decideVoice(pending,response)
                } finally { wav.fill(0) }
            } finally { pcm.fill(0) }
        } catch (error: Exception) {
            playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.APPROVAL, error)
            ActionConfirmation.cancelAll()
            if (error is SpeechEndGate.Rejected && !capture.cleanupConfirmed) throw error
            if(running.get() && !turnStopped.get()) status=AppText.get(R.string.rt_allow_unconfirmed, "음성 허용을 확인하지 못해 실행하지 않았습니다")
        } finally { uiPublication.waitFor(displayScope, ServiceUiPublication.Wait.NONE) }
    }
    }

    private fun promoteToPlayback() {
        val completed = CountDownLatch(1)
        main.post {
            try {
                if (running.get() && !cancelled.get()) {
                    startForeground(NOTIFICATION,
                        notification(getString(R.string.notif_reply), getString(R.string.notif_reply_text), false),
                        if (handsfree) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                }
            } catch (_: RuntimeException) {
                running.set(false)
                uiPublication.blocked(uiScope, ServiceUiPublication.Blocker.FOREGROUND_DENIED)
                status = AppText.get(R.string.rt_playback_denied, "Android가 음성 재생 서비스를 허용하지 않았습니다.")
            } finally {
                completed.countDown()
            }
        }
        if (!completed.await(3, TimeUnit.SECONDS)) throw VoiceBridgeClient.BridgeException("service_transition_timeout")
        if (!running.get()) throw VoiceBridgeClient.BridgeException("service_stopped")
    }

    /** Called with playbackMutex held for the whole utterance, never main-thread MediaPlayer. */
    private fun playAudio(bytes: ByteArray, keepPlaying: () -> Boolean, progress: Boolean, approval: Boolean): Long {
        check(captureValid() && !turnStopped.get() && keepPlaying()) { "playback_revoked" }
        val epoch = playbackEpoch.incrementAndGet()
        check(playback.beginPlaybackChecked(epoch, consentRevision.get(), { captureValid() && !turnStopped.get() && keepPlaying() })) { "playback_owner_blocked" }
        check(playback.enqueueBuffered(epoch, 0, "buffered_$epoch",
            when { progress -> SourceServicePlaybackAdapter.Kind.PROGRESS; approval -> SourceServicePlaybackAdapter.Kind.APPROVAL_NOTICE; else -> SourceServicePlaybackAdapter.Kind.FINAL }, bytes, speechPlaybackSpeed())) { "playback_rejected" }
        check(playback.startNext()) { "playback_start_rejected" }
        status = if (progress) AppText.get(R.string.rt_playing_progress, "작업 진행 상황을 재생 중입니다.") else AppText.get(R.string.rt_playing_reply, "Hermes 음성 답변을 재생 중입니다.")
        var joined = false
        try {
            val limit = SystemClock.elapsedRealtime() + 5 * 60_000L
            while (captureValid() && !turnStopped.get() && keepPlaying() && SystemClock.elapsedRealtime() < limit) {
                if (playback.awaitWorker(200)) { joined = true; break }
            }
            check(joined && playback.played(epoch)) { "playback_failed" }
            if (captureValid()) status = if (progress) AppText.get(R.string.rt_processing, "요청을 처리하고 있습니다.") else AppText.get(R.string.rt_finished, "음성 요청과 답변 재생이 끝났습니다.")
            return epoch
        } finally {
            if (!joined) {
                playback.cancelUtterance()
                val interrupted = Thread.interrupted()
                try { if (!playback.awaitWorker(5000)) playback.failCleanup() }
                catch (_: InterruptedException) { playback.failCleanup() }
                finally { if (interrupted) Thread.currentThread().interrupt() }
            }
            check(!playback.blocked()) { "playback_cleanup_unconfirmed" }
        }
    }

    /** IO/lifecycle worker only. OFF invalidates synchronously; reuse waits for actual joins. */
    private fun quiescePlayback(preserveController: Boolean = false): Boolean {
        if (preserveController) playback.cancelUtterance() else playback.off()
        progressConnection?.disconnect()
        val interrupted = Thread.interrupted()
        return try {
            val clean = if (preserveController) playback.cancelUtteranceAndJoin(5000) else playback.stopAndJoin(5000)
            if (!clean) playback.failCleanup()
            if (!clean) uiPublication.blocked(uiScope, ServiceUiPublication.Blocker.CLEANUP_UNCERTAIN)
            clean
        } catch (_: InterruptedException) {
            playback.failCleanup(); false
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun onCaptureCleanup(outcome: CaptureCleanup.Outcome, primary: Throwable?, displayScope: ServiceUiPublication.Scope = uiScope) {
        playback.captureCleanup(outcome, primary)
        uiPublication.captureClosed(displayScope, outcome.confirmed())
        // Approval catches errors to deny an action; it must not keep opening denied
        // monitor recorders after a permanent microphone/AEC cleanup failure.
        if (!outcome.confirmed()) invalidatePlayback(true)
    }

    private fun invalidatePlayback(stopService: Boolean = false) {
        activeSyntheticTrial?.stop()
        controllerDriver?.stop()
        playback.offAnd {
            turnStopped.set(true)
            if (stopService) { cancelled.set(true); running.set(false) }
            consentRevision.incrementAndGet()
            playbackRequestScope = ""
            uiPublication.off(uiScope, stopService)
        }
    }

    /** IO worker only; an ACK is scoped and must confirm cleanup, not merely HTTP 200. */
    private fun cancelRemote(token: String, scope: ScopedVoiceCancellation.Scope?, displaySession: ServiceUiPublication.Scope = uiScope): Boolean {
        if (scope == null) return !cancellation.blocked()
        val displayScope = ServiceUiPublication.Scope(displaySession.generation, scope.ingressOperationId)
        var confirmed = false
        try {
            VoiceBridgeClient.cancelTurn(token, scope)
            confirmed = true
        } catch (error: Exception) {
            playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.REQUEST, error)
            playback.failCleanup()
            status = AppText.get(R.string.rt_blocked_remote, "원격 요청 정리가 확인되지 않아 새 음성 요청을 차단했습니다")
        } finally {
            synchronized(cancellation) {
                cancellation.acknowledge(scope, confirmed)
                uiPublication.remote(displayScope, if (confirmed) ServiceUiPublication.Cleanup.CONFIRMED else ServiceUiPublication.Cleanup.UNCERTAIN, uiRemoteJoined)
            }
            settleOffCleanup(displayScope)
        }
        return confirmed
    }

    /** Called under the existing cancellation gate, atomically with cancel(). No IO here.
     * The lifecycle can join normally; destroy can schedule one independently tracked
     * bounded observer of this same target, without acquiring cancellation authority. */
    private fun startRemoteCancellationLocked(token: String, scope: ScopedVoiceCancellation.Scope?,
                                              displaySession: ServiceUiPublication.Scope) {
        check(Thread.holdsLock(cancellation))
        if (scope == null) return // repeated OFF must not replay cancellation
        check(remoteCancelWorker == null) { "cancel_worker_not_joined" }
        val worker = Thread({
            cancelRemote(token, scope, displaySession)
            settleOffCleanup(displaySession)
        }, "voice-cancel")
        remoteCancelWorker = worker
        remoteCancelLocalLease = microphoneLease
        remoteCancelJoinedWorker = null
        remoteCancelDestroyJoin = { scheduleDestroyRemoteJoinLocked(worker, displaySession) }
        uiRemoteJoined = false
        uiPublication.remote(displaySession, ServiceUiPublication.Cleanup.PENDING, false)
        remoteCancelJoined = {
            if (uiScope.generation == displaySession.generation) {
                uiRemoteJoined = true
                uiPublication.remote(displaySession, if (cancellation.blocked())
                    ServiceUiPublication.Cleanup.PENDING else ServiceUiPublication.Cleanup.CONFIRMED, true)
            }
        }
        try { worker.start() } catch (_: Throwable) {
            // Keep even a NEW/unstarted handle. Thread absence/start failure is not a join.
            cancellation.acknowledge(scope, false)
            uiPublication.remote(displaySession, ServiceUiPublication.Cleanup.UNCERTAIN, false)
        }
    }

    /** One slot per retained target, including failed/NEW observers. Never recursively watch
     * this observer: its termination only permits reusing the coordinator slot, not OFF.
     * The closure is installed by the target's originating service under cancellation. */
    private fun scheduleDestroyRemoteJoinLocked(target: Thread, displaySession: ServiceUiPublication.Scope) {
        check(Thread.holdsLock(cancellation))
        val previous = destroyRemoteJoin
        if (previous?.target === target) return
        check(previous == null || (!destroyRemoteJoinBlocked())) { "destroy_join_still_retained" }
        val cleanup = DestroyRemoteJoin(target)
        destroyRemoteJoin = cleanup // registration precedes construction/start, including failures
        try {
            val observer = Thread({
                try {
                    if (!joinRemoteCancellationWorker(target)) {
                        synchronized(cancellation) {
                            cleanup.failure = "target_not_joined"
                            cleanup.outcome = DestroyJoinOutcome.FAILED
                        }
                    } else {
                        synchronized(cancellation) { cleanup.outcome = DestroyJoinOutcome.SUCCEEDED }
                    }
                } catch (_: InterruptedException) {
                    synchronized(cancellation) {
                        cleanup.failure = "join_interrupted"
                        cleanup.outcome = DestroyJoinOutcome.FAILED
                    }
                    Thread.currentThread().interrupt()
                } catch (_: Throwable) {
                    synchronized(cancellation) {
                        cleanup.failure = "join_failed"
                        cleanup.outcome = DestroyJoinOutcome.FAILED
                    }
                }
                try { settleOffCleanup(displaySession) }
                catch (_: Throwable) {
                    synchronized(cancellation) {
                        if (cleanup.failure == null) cleanup.failure = "settlement_failed"
                    }
                }
            }, "voice-destroy-join")
            cleanup.worker = observer // retained even if start leaves it NEW
            observer.start()
        } catch (_: Throwable) {
            cleanup.failure = "observer_start_failed"
            cleanup.outcome = DestroyJoinOutcome.FAILED
            uiPublication.remote(displaySession, ServiceUiPublication.Cleanup.UNCERTAIN, uiRemoteJoined)
        }
    }

    private fun joinRemoteCancellation(): Boolean {
        val worker = synchronized(cancellation) { remoteCancelWorker } ?: return true
        return joinRemoteCancellationWorker(worker)
    }

    private fun joinRemoteCancellationWorker(worker: Thread): Boolean {
        check(!Thread.holdsLock(cancellation))
        if (worker === Thread.currentThread()) return false
        worker.join(5000) // never Android main; callers are lifecycle or the destroy observer
        return synchronized(cancellation) {
            if (worker.state != Thread.State.TERMINATED) false // NEW is not joined
            else if (remoteCancelJoinedWorker === worker) true // concurrent exact-target join
            else if (remoteCancelWorker !== worker) false
            else {
                remoteCancelWorker = null
                remoteCancelJoinedWorker = worker
                remoteCancelDestroyJoin = null
                val joined = remoteCancelJoined
                remoteCancelJoined = null
                joined?.invoke() // display only, under the existing gate, for the captured owner
                true
            }
        }
    }

    private fun stopConversation(token: String) {
        // Capture before clearing IDs; accept and STOP share this boundary.
        val scope = synchronized(cancellation) {
            turnStopped.set(true)
            uiPublication.off(uiScope, false)
            cancellation.cancel().also { activeRequestId = "" }
        }
        playback.cancelConversation { playbackRequestScope = "" }
        ActionConfirmation.cancelAll()
        McpService.actionsPaused = true
        connection?.disconnect()
        progressConnection?.disconnect()
        val localClean = quiescePlayback(true)
        val remoteClean = cancelRemote(token, scope)
        check(localClean && remoteClean) { "cancel_cleanup_unconfirmed" }
        status = AppText.get(R.string.rt_stop, "스탑 · 요청 권한과 재생을 중단하고 호출어 대기로 돌아갑니다")
    }

    private fun cancelActive(message: String) {
        val displaySession = uiScope
        invalidatePlayback(true) // revoke effects before dispatching exact old-request cleanup
        val token = getSharedPreferences("lc_config", MODE_PRIVATE).getString("voice_token", "") ?: ""
        synchronized(cancellation) {
            turnStopped.set(true); cancelled.set(true); running.set(false)
            uiPublication.off(uiScope, true)
            val scope = cancellation.cancel().also { activeRequestId = "" }
            startRemoteCancellationLocked(token, scope, displaySession)
        }
        ActionConfirmation.cancelAll()
        // Request STOP pauses actions; it is not host /off or an MCP service shutdown.
        McpService.actionsPaused = true
        val playbackBlocked = playback.blocked()
        synchronized(cancellation) {
            // A recreated OFF-only service has no lifecycle/status authority. Keep the
            // prior owner's genuine failure (and any still-valid completion callback).
            val mayPublish = activeStartId != 0 && !playbackBlocked &&
                (offStatusOwner == null || status == offPublishedStatus)
            if (mayPublish) {
                offStatusOwner = Any().also { cleanupStatusOwner = it }
                offCompletionMessage = message
                status = AppText.get(R.string.rt_cancelling, "취소 요청 중 · 원격 요청과 로컬 정리를 확인하고 있습니다")
                offPublishedStatus = status
            }
        }
        settleOffCleanup(displaySession)
        submitRequested.set(false)
        progressConnection?.disconnect()
        progressConnection = null
        connection?.disconnect()
        connection = null
        main.removeCallbacks(deadline)
        playback.off()
        handsfreeActive = false
        VoiceWidget.update(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun finish(startId: Int) {
        invalidatePlayback(true) // revoke effects before synchronous or worker cancellation
        val abandoned = synchronized(cancellation) {
            turnStopped.set(true); cancelled.set(true); running.set(false)
            uiPublication.off(uiScope, true)
            cancellation.cancel().also { activeRequestId = "" }
        }
        if (abandoned != null) {
            val token = getSharedPreferences("lc_config", MODE_PRIVATE).getString("voice_token", "") ?: ""
            cancelRemote(token, abandoned)
        }
        val cancelJoined = try { joinRemoteCancellation() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!cancelJoined) playback.failCleanup()
        val playbackClean = quiescePlayback()
        // Failed/OFF request closure is only an assertion, never a success receipt.
        // Preserve the exact old renderer while remote cancellation remains in flight.
        if (normalStreaming && requestRender != null && cancelJoined && playbackClean) {
            try { settleRequestRender() }
            catch (error: Exception) { playback.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.REQUEST, error); playback.failCleanup() }
        }
        val controllerClean = try { controllerDriver?.stopAndJoin(3000) ?: true }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); playback.failCleanup(); false }
        val clean = playbackClean && controllerClean && !playback.blocked()
        ActionConfirmation.cancelAll()
        activeRequestId=""
        handsfreeActive = false
        running.set(false)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        main.removeCallbacks(widgetTick)
        VoiceWidget.update(this)
        submitRequested.set(false)
        main.removeCallbacks(deadline)
        progressConnection?.disconnect()
        progressConnection = null
        connection?.disconnect()
        connection = null
        main.post {
            if (activeStartId == startId && !running.get()) {
                playback.off()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        synchronized(cancellation) {
            if (activeStartId == startId) {
                if (clean) {
                    if (localCleanupStartId != startId) cleanupReleasePending = true
                    localCleanupStartId = startId
                } else {
                    localCleanupStartId = null
                    status = AppText.get(R.string.rt_mic_reuse_blocked, "재생 정리가 확인되지 않아 마이크 재사용을 차단했습니다")
                }
                // Lease release is still pending here. Only settleOffCleanup can confirm local OFF.
                uiPublication.settle(uiScope, false, !cancellation.blocked(), uiRemoteJoined, !clean)
            }
        }
        settleOffCleanup()
    }

    /** Both completion edges call this; neither edge alone proves reusable OFF. */
    private fun settleOffCleanup(displayScope: ServiceUiPublication.Scope = uiScope) {
        // Do not acquire the playback owner under cancellation (capture uses the reverse order).
        val playbackBlocked = playback.blocked()
        val owner = synchronized(cancellation) {
            val cleanupUncertain = playbackBlocked || destroyRemoteJoin?.failure != null
            // A late lifecycle join cannot consume an observer's unpublished timeout/throw.
            // Do not wait for observer termination here: the observer settles its own success.
            val observerSucceeded = destroyRemoteJoin?.let { it.outcome == DestroyJoinOutcome.SUCCEEDED } ?: true
            if (localCleanupStartId == activeStartId && cleanupReleasePending &&
                observerSucceeded &&
                !cleanupUncertain && !cancellation.blocked() && uiRemoteJoined && remoteCancelWorker == null) {
                // Local proof is not lease ownership. The atomic release checks the
                // actual process owner even after recreation or a delayed callback.
                if (MicrophoneOwner.release(microphoneLease)) {
                    cleanupReleasePending = false
                    if (remoteCancelLocalLease === microphoneLease) remoteCancelLocalLease = null
                }
            }
            uiPublication.settle(displayScope,
                localCleanupStartId == activeStartId && !cleanupReleasePending,
                !cancellation.blocked(), uiRemoteJoined, cleanupUncertain)
            offStatusOwner
        } ?: return
        // Main-thread publication serializes with accepted START and repeated CANCEL.
        main.post {
            val blocked = playback.blocked()
            var updated = false
            synchronized(cancellation) {
                if (cleanupStatusOwner === owner && offStatusOwner === owner &&
                    !running.get() && status == offPublishedStatus) {
                    status = when {
                        blocked || destroyRemoteJoin?.failure != null -> AppText.get(R.string.rt_blocked_cleanup, "요청 정리가 확인되지 않아 새 음성 요청을 차단했습니다")
                        cancellation.blocked() || !uiRemoteJoined || remoteCancelWorker != null -> AppText.get(R.string.rt_cancelling, "취소 요청 중 · 원격 요청과 로컬 정리를 확인하고 있습니다")
                        localCleanupStartId != activeStartId || cleanupReleasePending -> AppText.get(R.string.rt_cancelled_local, "요청 권한을 취소했습니다 · 로컬 정리 대기 중")
                        else -> offCompletionMessage ?: return@synchronized
                    }
                    offPublishedStatus = status
                    updated = true
                }
            }
            if (updated) VoiceWidget.update(this)
        }
    }

    private fun notification(title: String, message: String, canSubmit: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val cancelIntent = PendingIntent.getService(this, 401,
            Intent(this, VoiceRequestService::class.java).setAction(CANCEL_REQUEST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_stop), cancelIntent)
        if (canSubmit) {
            val submitIntent = PendingIntent.getService(this, 402,
                Intent(this, VoiceRequestService::class.java).setAction(SUBMIT_REQUEST),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_menu_send, getString(R.string.notif_send), submitIntent)
        }
        return builder.build()
    }

    private fun safeCategory(error: Exception): String = when (error) {
        is VoiceBridgeClient.BridgeException -> VoiceSttOutcome.message(error.sttReason, error.sttCleanupConfirmed)
            ?: AppText.get(R.string.rt_request_failed_generic, "음성 요청 처리에 실패했습니다")
        is IllegalArgumentException -> "request_invalid"
        else -> error.javaClass.simpleName
    }

    override fun onDestroy() {
        val displaySession = uiScope
        invalidatePlayback(true) // logical OFF first; cleanup remains obligated
        val token = getSharedPreferences("lc_config", MODE_PRIVATE).getString("voice_token", "") ?: ""
        synchronized(cancellation) {
            turnStopped.set(true); cancelled.set(true); running.set(false)
            uiPublication.off(uiScope, true)
            val abandoned = cancellation.cancel().also { activeRequestId = "" }
            startRemoteCancellationLocked(token, abandoned, displaySession)
            remoteCancelDestroyJoin?.invoke() // schedules old owner's exact target; never joins main
        }
        playback.destroy()
        settleOffCleanup(displaySession) // remote evidence alone cannot supply local proof
        handsfreeActive = false
        main.removeCallbacks(widgetTick)
        VoiceWidget.update(this)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        cancelled.set(true)
        running.set(false)
        submitRequested.set(false)
        main.removeCallbacks(deadline)
        progressConnection?.disconnect()
        progressConnection = null
        connection?.disconnect()
        connection = null
        // The capture worker owns stop/release and publishes its outcome. Native stop
        // must not block the main thread or race that worker's independent cleanup.
        playback.off()
        VoiceDiagnostics.trace("service_destroy")
        VoiceDiagnostics.flushAsync() // do not claim clean-close before remaining workers exit
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Display-only platform cleanup must never precede voice revocation or
        // retained exact-target cancellation/observer scheduling. Detach the local
        // handle first; OptionalStatusOverlay retains its attachment-generation fence.
        val displayLease = statusOverlayLease; statusOverlayLease = null
        try { displayLease?.close() } catch (_: RuntimeException) { /* optional display only */ }
        super.onDestroy()
    }

    private class WipingByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun takeAndWipe(): ByteArray = buf.copyOf(count).also { wipe() }
        fun wipe() {
            Arrays.fill(buf, 0)
            reset()
        }
    }

    internal class MemoryAudioSource(private val bytes: ByteArray) : MediaDataSource() {
        @Volatile private var closed = false
        override fun getSize(): Long = if (closed) -1 else bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (closed || position < 0 || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }
        @Throws(IOException::class)
        override fun close() {
            if (!closed) {
                closed = true
                Arrays.fill(bytes, 0)
            }
        }
    }
}
