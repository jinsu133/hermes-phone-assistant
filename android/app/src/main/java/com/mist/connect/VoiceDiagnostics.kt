package com.mist.connect
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Metadata only. String-taking legacy APIs sanitize at this boundary, not at callers. */
internal object VoiceDiagnostics {
    val wakes=AtomicLong(); val captures=AtomicLong(); val silences=AtomicLong(); val stops=AtomicLong(); val uploads=AtomicLong()
    val approvals=AtomicLong(); val approvalCaptures=AtomicLong(); val allowed=AtomicLong(); val denied=AtomicLong(); val resumed=AtomicLong()
    private data class Failure(val stage: String, val type: String, val at: Long)
    private val firstFailure = AtomicReference<Failure?>()
    private val firstPlayback = AtomicReference<Failure?>()
    private val traceLock = Any()
    private val traceRing = ArrayDeque<String>()
    private val unattachedDrops = AtomicLong()
    @Volatile private var journal: VoiceDiagnosticJournal? = null

    /** Starts a writer only. Directory scanning/open/fsync happens on that writer. */
    @Synchronized fun attachLogFile(dir: java.io.File) {
        if (journal != null) return
        synchronized(traceLock) {
            journal = VoiceDiagnosticJournal(dir, android.os.Process.myPid().toLong(), BuildConfig.VERSION_CODE.toLong()).also {
                it.reportPreAttachDrops(unattachedDrops.get())
            }
        }
    }
    private fun record(record: VoiceDiagnosticJournal.Record) {
        val bounded = VoiceDiagnosticJournal.display(record)
        synchronized(traceLock) {
            traceRing.addLast(bounded)
            while (traceRing.size > 48) traceRing.removeFirst()
            val sink = journal
            if (sink == null) unattachedDrops.incrementAndGet() else sink.offer(record)
        }
    }
    fun trace(event: String) { record(VoiceDiagnosticJournal.trace(event)) }
    fun playbackFailed(stage: String, category: String) {
        val safe = VoiceDiagnosticJournal.playback(stage, category)
        firstPlayback.compareAndSet(null, Failure(VoiceDiagnosticJournal.stage(stage), VoiceDiagnosticJournal.category(category), System.currentTimeMillis()))
        record(safe)
    }
    fun reportException(scope: String, error: Throwable?) {
        if (error == null) return
        firstPlayback.compareAndSet(null, Failure(VoiceDiagnosticJournal.stage(scope), VoiceDiagnosticJournal.type(error), System.currentTimeMillis()))
        record(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.EXCEPTION, scope, error))
    }
    @Suppress("UNUSED_PARAMETER")
    fun failed(stage: String, error: Throwable?, detail: String = "") {
        // detail is intentionally discarded, even for legacy callers with exception messages.
        firstFailure.compareAndSet(null, Failure(VoiceDiagnosticJournal.stage(stage), VoiceDiagnosticJournal.type(error), System.currentTimeMillis()))
        record(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.FAILURE, stage, error))
    }
    fun nativeOpen(opened: Boolean, error: Throwable?) { record(VoiceDiagnosticJournal.nativeOpen(opened, error)) }
    fun duplexExit(worker: DuplexCaptureWorker?, outcome: CaptureCleanup.Outcome, primary: Throwable?) {
        // One summary on actual capture exit, never a per-10ms logger.
        record(VoiceDiagnosticJournal.duplex(worker != null, worker?.failureSnapshot(), outcome.confirmed(), primary,
            outcome.aecFailure ?: outcome.stopFailure ?: outcome.microphoneFailure))
        if (primary != null) record(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.EXCEPTION, "capture_primary_failed", primary))
        for ((stage, failure) in listOf("mic_stop" to outcome.stopFailure, "aec_release" to outcome.aecFailure, "mic_release" to outcome.microphoneFailure)) {
            if (failure != null) record(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.EXCEPTION, stage, failure))
        }
        journal?.flushAsync()
    }
    fun flushAsync() { journal?.flushAsync() }
    /** Explicit diagnostic lifecycle only; do not close while service workers can still emit. */
    fun closeAsync() { journal?.closeAsync() }
    fun clearFailure() { firstFailure.set(null); firstPlayback.set(null) } // journal is never cleared
    fun status(): JSONObject {
        val failure=firstFailure.get(); val playback=firstPlayback.get(); val j=journal
        val complete = j?.testReady() == true && unattachedDrops.get() == 0L
        return JSONObject().put("wake_hits",wakes.get()).put("captures",captures.get())
            .put("silent_captures",silences.get()).put("local_stops",stops.get()).put("uploads",uploads.get())
            .put("approval_prompts",approvals.get()).put("approval_captures",approvalCaptures.get())
            .put("voice_allowed",allowed.get()).put("voice_denied",denied.get()).put("actions_resumed",resumed.get())
            .put("phase",VoiceRequestService.status).put("listening",VoiceRequestService.handsfreeActive)
            .put("failure_stage",failure?.stage ?: "").put("failure_category",failure?.type ?: "")
            .put("failure_detail","").put("failure_at",failure?.at ?: 0L)
            .put("playback_stage",playback?.stage ?: "").put("playback_category",playback?.type ?: "")
            .put("playback_at",playback?.at ?: 0L).put("playback_trace",traceSnapshot())
            .put("journal_schema",1).put("journal_directory","voice-journal")
            .put("journal_session",j?.session ?: "").put("journal_run_id",j?.session ?: "")
            .put("journal_health",if (unattachedDrops.get() > 0) "incomplete" else j?.health() ?: "unattached")
            .put("diagnostic_test_ready",complete).put("journal_problem",j?.problem() ?: "unattached")
            .put("journal_dropped",j?.dropped() ?: unattachedDrops.get())
            .put("journal_written",j?.written() ?: 0L).put("journal_sequence",j?.sequence() ?: 0L)
            .put("journal_accepted",j?.accepted() ?: 0L).put("journal_pending",j?.pending() ?: 0)
            .put("journal_stopped",j?.stopped() ?: false)
            .put("journal_previous_unclosed",j?.previousUnclosed() ?: 0L)
            .put("journal_previous_incomplete_close",j?.previousLossyCloses() ?: 0)
            .put("journal_version",BuildConfig.VERSION_CODE).put("journal_pid",android.os.Process.myPid())
            .put("legacy_trace_file","preserved_not_updated").put("latency_scope","app_stage_monotonic_not_speech_end")
    }
    private fun traceSnapshot(): JSONArray = synchronized(traceLock) {
        JSONArray().also { out -> for (line in traceRing) out.put(line) }
    }
}
