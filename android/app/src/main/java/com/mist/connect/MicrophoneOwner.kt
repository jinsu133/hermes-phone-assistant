package com.mist.connect

import java.util.concurrent.atomic.AtomicReference

/** One process-wide microphone lease shared by probe and voice-request services. */
internal object MicrophoneOwner {
    private val legacyOwner = Any()
    private val held = AtomicReference<Any?>(null)
    @JvmStatic fun claim(): Boolean = claim(legacyOwner)
    @JvmStatic fun release() { release(legacyOwner) }
    // Compare identity at the effect edge: a retired service cannot release a new lease.
    @JvmStatic fun claim(owner: Any): Boolean = held.compareAndSet(null, owner)
    @JvmStatic fun release(owner: Any): Boolean = held.compareAndSet(owner, null)
}
