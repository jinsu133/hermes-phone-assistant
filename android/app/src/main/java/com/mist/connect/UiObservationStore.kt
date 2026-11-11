package com.mist.connect

import java.io.Closeable

internal data class LocalUiFacts(
    val now: Long, val locked: Boolean = false, val microphoneGranted: Boolean = false,
    val controlConnected: Boolean = false, val keysPresent: Boolean = false,
    val readiness: SetupReadiness.Phase = SetupReadiness.Phase.UNCHECKED, val readinessAt: Long = 0
) {
    fun displayedReadiness() = when {
        !keysPresent -> SetupReadiness.Phase.UNPAIRED
        now - readinessAt >= 30_000L -> SetupReadiness.Phase.UNCHECKED
        else -> readiness
    }
}

/** Real repository core. Scheduler MUST enqueue (never inline). One pending delivery;
 * no callback under monitor, no producer lock, no commands or Android dependencies.
 * The Android adapter and inert connected tests use this same implementation. */
internal class UiObservationStore(epoch: String, private val enqueue: (Runnable) -> Unit) {
    private val lock = Any()
    private var state = CompanionUiState(epoch)
    private var channel: Any? = null
    private var sequence = 0L
    private var pending = false
    private var listenerId = 0L
    private val listeners = linkedMapOf<Long, (CompanionUiState) -> Unit>()
    fun read(): CompanionUiState = synchronized(lock) { state }
    private fun aggregate(s: ServiceUiPublication.Snapshot?, local: LocalUiFacts): CompanionUiState =
        CompanionUiState(state.observationEpoch, ++sequence, local.now,
            readiness = local.displayedReadiness(), readinessObservedAtMillis = local.readinessAt, locked = local.locked,
            microphoneGranted = local.microphoneGranted, controlConnected = local.controlConnected)
            .withService(s, if (s === state.service) state.serviceReceivedAtMillis else local.now)
    /** Called only after an explicit read of the authoritative read-only channel pointer. */
    fun rebind(source: Any?, initial: ServiceUiPublication.Snapshot?, local: LocalUiFacts) {
        synchronized(lock) {
            if (channel === source) return
            channel = source
            state = CompanionUiReducer.rebind(state, aggregate(initial, local))
        }
        signal()
    }
    fun refresh(source: Any?, snapshot: ServiceUiPublication.Snapshot?, local: LocalUiFacts) {
        synchronized(lock) {
            // Old producer data is rejected; independent fresh local facts still merge.
            val accepted = if (channel === source) snapshot else state.service
            state = CompanionUiReducer.observe(state, aggregate(accepted, local))
        }
        signal()
    }
    fun offRequested() {
        synchronized(lock) { state = CompanionUiReducer.offRequested(state) }
        signal()
    }
    fun subscribe(listener: (CompanionUiState) -> Unit): Closeable {
        val id = synchronized(lock) {
            check(listeners.size < 8) { "display_subscriber_bound" }
            (++listenerId).also { listeners[it] = listener }
        }
        signal()
        return Closeable { synchronized(lock) { listeners.remove(id) }; Unit }
    }
    private fun signal() {
        val schedule = synchronized(lock) {
            if (pending || listeners.isEmpty()) false else { pending = true; true }
        }
        if (schedule) enqueue(Runnable { deliver() }) // outside repository and owner locks
    }
    private fun deliver() {
        val batch = synchronized(lock) {
            pending = false
            state to listeners.keys.toList()
        }
        batch.second.forEach { id ->
            val listener = synchronized(lock) { listeners[id] }
            // A detached listener is not retained by the queued task.
            try { listener?.invoke(batch.first) } catch (_: RuntimeException) {
                // One failed display consumer cannot starve other observers; no authority changes.
            }
        }
    }
    internal fun subscriberCount(): Int = synchronized(lock) { listeners.size }
}
