package com.mist.connect

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** One local decoder; a bounded volatile pre-roll is released after each wake. */
internal class HandsfreeCapture(
    context: Context, private val active: () -> Boolean,
    private val state: (String) -> Unit, private val owning: (AudioRecord?) -> Unit,
    private val cleanup: (CaptureCleanup.Outcome, Throwable?) -> Unit = { _, _ -> },
    private val typedState: (ServiceUiPublication.CaptureEvent) -> Unit = {}
) : AutoCloseable {
    private val detector = KoreanWakeDetector(context, active) { _, _ -> }
    var cleanupConfirmed = true
        private set
    var wasStopped = false
        private set
    fun nextRequest(followup: Boolean = false, start: ((() -> Unit) -> Boolean)? = null): ByteArray? {
        wasStopped = false
        detector.reset()
        detector.prepareStop() // Load before opening the microphone, never mid-command.
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        var audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 128000))
        // A transient recorder failure must not end handsfree listening. Recovery is bounded
        // and keeps ring/capture state so the current utterance is not silently truncated.
        var micRecoveries = 0
        val maxMicRecoveries = 5
        val ring = ShortArray(48000)
        val block = ShortArray(1600)
        var ringPos = 0
        var ringSize = 0
        var totalSamples = 0L
        val capture = object : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }
        var triggered = followup
        val end = SpeechEndGate(if (followup) 30000 else 7000)
        var followupSpeechStarted = false
        var captureStartedAt = 0L
        var primary: Throwable? = null
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            owning(audio)
            if (!active()) return null
            if (start != null) { if (!start { audio.startRecording() }) return null }
            else audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            typedState(if (followup) ServiceUiPublication.CaptureEvent.FOLLOW_UP else ServiceUiPublication.CaptureEvent.WAKE_WAIT)
            if (triggered) captureStartedAt = android.os.SystemClock.elapsedRealtime()
            state(if (followup) AppText.get(R.string.rt_followup, "이어 말하세요 · 30초 동안 기다립니다") else AppText.wakeWait())
            while (active()) {
                if (triggered) end.checkWallTime(android.os.SystemClock.elapsedRealtime() - captureStartedAt)
                val n = audio.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) {
                    micRecoveries++
                    check(micRecoveries <= maxMicRecoveries) { "mic_read_failed" }
                    try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Throwable) { }
                    try { audio.release() } catch (_: Throwable) { }
                    Thread.sleep(150L * micRecoveries)
                    if (!active()) return null
                    audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 128000))
                    check(audio.state == AudioRecord.STATE_INITIALIZED)
                    owning(audio)
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    continue
                }
                micRecoveries = 0
                if (n == 0) { Thread.sleep(20); continue }
                totalSamples += n
                if (!triggered) {
                    for (i in 0 until n) { ring[ringPos] = block[i]; ringPos = (ringPos+1)%ring.size; ringSize = minOf(ringSize+1,ring.size) }
                    if (detector.accept(block,n)) {
                        VoiceDiagnostics.wakes.incrementAndGet()
                        triggered = true
                        captureStartedAt = android.os.SystemClock.elapsedRealtime()
                        typedState(ServiceUiPublication.CaptureEvent.CAPTURING)
                        state(AppText.get(R.string.rt_capturing, "요청 듣는 중 · 말을 마치면 자동 전송"))
                        val allowed = minOf(ringSize.toLong(), maxOf(0L, totalSamples-detector.wakeEndSample)).toInt()
                        val start=(ringPos-allowed+ring.size)%ring.size
                        for (i in 0 until allowed) { val v=ring[(start+i)%ring.size].toInt(); capture.write(v and 255); capture.write((v ushr 8) and 255) }
                        if (allowed > 0) {
                            val seed = ShortArray(allowed) { ring[(start+it)%ring.size] }
                            try {
                                detector.acceptStop(seed, allowed)
                                if (detector.stopped) { VoiceDiagnostics.stops.incrementAndGet(); wasStopped = true; return ByteArray(0) }
                                end.accept(seed, allowed, detector.endpointEvidence)
                            } finally { seed.fill(0) }
                        }
                        ring.fill(0)
                    }
                } else {
                    detector.acceptStop(block,n)
                    if (detector.stopped) { VoiceDiagnostics.stops.incrementAndGet(); wasStopped = true; return ByteArray(0) }
                    val finished = end.accept(block,n,detector.endpointEvidence)
                    if (followup && !followupSpeechStarted && end.hasSpeech()) {
                        val first = (ringPos-ringSize+ring.size)%ring.size
                        for (i in 0 until ringSize) { val v=ring[(first+i)%ring.size].toInt(); capture.write(v and 255); capture.write((v ushr 8) and 255) }
                        ring.fill(0); ringSize = 0; followupSpeechStarted = true
                    }
                    if (!followup || followupSpeechStarted) {
                        // The native STT input remains capped at 30 seconds. Never send
                        // partial audio when that separate transport boundary is exceeded.
                        if (capture.size().toLong() + n * 2L > SpeechEndGate.MAX_UTTERANCE_MS * 32L)
                            throw SpeechEndGate.Rejected(SpeechEndGate.Decision.SAFETY_LIMIT)
                        for (i in 0 until n) { val v=block[i].toInt(); capture.write(v and 255); capture.write((v ushr 8) and 255) }
                    } else {
                        for (i in 0 until n) { ring[ringPos] = block[i]; ringPos = (ringPos+1)%ring.size; ringSize = minOf(ringSize+1,ring.size) }
                    }
                    // Decoder cost counts against the wall bound too. Neither a sample
                    // cap nor a stalled/noisy decoder may turn a clipped buffer into a request.
                    end.checkWallTime(android.os.SystemClock.elapsedRealtime() - captureStartedAt)
                    if (finished) {
                        end.requireCompleteOrEmpty()
                        if(end.decision() == SpeechEndGate.Decision.COMPLETE) { VoiceDiagnostics.captures.incrementAndGet(); return capture.toByteArray() }
                        VoiceDiagnostics.silences.incrementAndGet(); return ByteArray(0)
                    }
                }
                block.fill(0)
            }
            return null
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val outcome = CaptureCleanup.release(
                { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() },
                null, { audio.release() })
            cleanupConfirmed = cleanupConfirmed && outcome.confirmed()
            cleanup(outcome, primary)
            if (outcome.confirmed()) owning(null)
            ring.fill(0); block.fill(0); capture.wipe()
            val failure = outcome.preserve(primary)
            if (primary == null && failure != null) throw failure
        }
    }
    /** Local-only stop listener while the request worker waits or plays TTS. */
    fun monitorStop(until: () -> Boolean, start: ((() -> Unit) -> Boolean)? = null,
                    reference: () -> PlaybackReference? = { null },
                    legacyStopAllowed: () -> Boolean = { false },
                    handoff: DuplexTurnHandoff? = null, onStop: () -> Unit) {
        detector.reset()
        detector.prepareStop() // Load before opening the microphone, never mid-command.
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min,128000))
        val block = ShortArray(160)
        var filled = 0
        var readFrames = 0L
        val timestamp = android.media.AudioTimestamp()
        var duplex: DuplexCaptureWorker? = null
        val endpoint = SpeechEndGate()
        var endpointStarted = 0L
        val qualifiedFrames = object : DuplexCaptureWorker.QualifiedFrames {
            override fun candidate(scope: PlaybackReference.Scope): Boolean {
                // Invoked only behind BOTH hard-false acoustic gates in the worker.
                if (handoff?.request(scope, true) != true) return false
                detector.reset()
                endpointStarted = android.os.SystemClock.elapsedRealtime()
                return true
            }
            override fun residual(pcm: ShortArray): Boolean {
                detector.acceptStop(pcm, pcm.size)
                // A decoded STOP is not a qualified STOP; no approval/STOP effect here.
                endpoint.checkWallTime(android.os.SystemClock.elapsedRealtime() - endpointStarted)
                if (!endpoint.accept(pcm, pcm.size, detector.endpointEvidence)) return false
                endpoint.requireCompleteOrEmpty()
                if (endpoint.decision() != SpeechEndGate.Decision.COMPLETE) handoff?.close()
                return endpoint.decision() == SpeechEndGate.Decision.COMPLETE
            }
            override fun endpoint(command: ContinuousCaptureHandoff.Command?) { handoff?.endpoint(command) }
        }
        var aec: android.media.audiofx.AcousticEchoCanceler? = null
        var primary: Throwable? = null
        try {
            duplex = DuplexCaptureWorker.openIfPackaged { VoiceDiagnostics.nativeOpen(false, it) } // before recording
            if (duplex != null) VoiceDiagnostics.nativeOpen(true, null)
            aec = if (duplex == null && android.media.audiofx.AcousticEchoCanceler.isAvailable())
                android.media.audiofx.AcousticEchoCanceler.create(audio.audioSessionId) else null
            aec?.enabled = true
            owning(audio)
            if (start != null) { if (!start { audio.startRecording() }) return }
            else { if (!active()) return; audio.startRecording() }
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) typedState(ServiceUiPublication.CaptureEvent.MONITOR)
            while ((active() && until() && handoff?.scope() == null) || handoff?.collecting() == true) {
                if (endpointStarted != 0L) endpoint.checkWallTime(android.os.SystemClock.elapsedRealtime() - endpointStarted)
                val n = audio.read(block,filled,block.size-filled,AudioRecord.READ_NON_BLOCKING)
                check(n >= 0)
                if (n == 0) { Thread.sleep(5); continue }
                filled += n; readFrames += n
                if (filled < block.size) continue
                val ref = reference()
                if (ref == null) duplex?.referenceUnavailable()
                if (duplex != null && ref != null && !duplex.failed()) {
                    if (audio.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                        && timestamp.framePosition >= readFrames) {
                        val firstNanos = timestamp.nanoTime - (timestamp.framePosition-readFrames+160)*62500L
                        duplex.accept(block,firstNanos,ref,System.nanoTime(),qualifiedFrames)
                    } else duplex.timestampUnavailable() // never invent timing from read-return time
                }
                if (duplex?.failed() == true && handoff?.scope() != null) handoff.close()
                // AEC enabled or VAD speech is NOT proof that a STOP came from the user.
                // The retained owner allows legacy STOP only before ANY possible self playback.
                if (legacyStopAllowed()) {
                    detector.acceptStop(block,block.size)
                    if (detector.stopped && legacyStopAllowed()) {
                        block.fill(0); VoiceDiagnostics.stops.incrementAndGet(); onStop(); return
                    }
                }
                block.fill(0); filled = 0
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val outcome = CaptureCleanup.release(
                { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() },
                { CaptureCleanup.release(null, { duplex?.close() }, { aec?.release() }).preserve(null)?.let { throw it } },
                { audio.release() })
            cleanupConfirmed = cleanupConfirmed && outcome.confirmed()
            // SAME capture finally ends retention; neither old-host CLEAN nor endpoint
            // alone proves native cleanup. The owner callback freezes coverage before
            // signalling its single renderer to stop and join.
            VoiceDiagnostics.duplexExit(duplex, outcome, primary)
            handoff?.captureEnded(outcome.confirmed() && primary == null)
            cleanup(outcome, primary)
            if (outcome.confirmed()) owning(null)
            block.fill(0)
            val failure = outcome.preserve(primary)
            if (primary == null && failure != null) throw failure
        }
    }
    override fun close() { detector.close() }
}
