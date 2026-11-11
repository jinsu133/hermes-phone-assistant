package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

/** Real process-wide lease, fake native resources only; no Android microphone APIs. */
class CaptureGlobalOwnershipTest {
    @Test fun uncertainAecOrMicCleanupNeverReleasesGlobalLease() {
        for (failAec in listOf(true, false)) {
            assertTrue(MicrophoneOwner.claim())
            try {
                val owner = SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.BUFFERED_SERVICE)
                assertTrue(owner.begin(1, 0))
                assertTrue(owner.captureIdle(1, 0, { true }, {}))
                var aecAttempts = 0
                var micAttempts = 0
                val result = CaptureCleanup.release({}, {
                    aecAttempts++
                    if (failAec) throw IllegalStateException("aec_fixture")
                }, {
                    micAttempts++
                    if (!failAec) throw IllegalStateException("mic_fixture")
                })
                owner.captureCleanup(result)
                owner.endListening(1, result.confirmed())
                // The service finish boundary: only confirmed stop/join permits release.
                if (owner.stopAndJoin(50)) MicrophoneOwner.release()
                assertEquals(1, aecAttempts)
                assertEquals(1, micAttempts)
                assertTrue(owner.blocked())
                assertFalse(MicrophoneOwner.claim())
                assertFalse(owner.begin(2, 1))
            } finally {
                // Fixture owns no real resource; reset only after assertions for other tests.
                MicrophoneOwner.release()
            }
        }
    }
}
