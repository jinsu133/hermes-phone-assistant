package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

/** A long wait on the host must not age the status past the overlay's source TTL. */
class ServiceUiHeartbeatTest {
    private val gate = Any()
    private var now = 1_000L
    private val publisher = ServiceUiPublication(gate) { now }

    @Test fun heartbeatRestampsOnlyWhenOlderThanTheThrottle() {
        val scope = publisher.start(1, ServiceUiPublication.Mode.MANUAL)
        publisher.phase(scope, ServiceUiPublication.Phase.WAITING_REPLY)
        val before = publisher.read()
        now += 5_000L
        assertFalse(publisher.heartbeat(scope, VoiceRequestService.HEARTBEAT_MS))
        assertSame(before, publisher.read())
        now += 6_000L
        assertTrue(publisher.heartbeat(scope, VoiceRequestService.HEARTBEAT_MS))
        val after = publisher.read()
        assertEquals(now, after.sourceAtMillis)
        assertEquals(ServiceUiPublication.Phase.WAITING_REPLY, after.phase)
        assertEquals(before.generation, after.generation)
        assertTrue(after.sequence > before.sequence)
    }

    @Test fun heartbeatKeepsAWaitingStatusInsideTheSourceTtl() {
        val scope = publisher.start(1, ServiceUiPublication.Mode.MANUAL)
        publisher.phase(scope, ServiceUiPublication.Phase.WAITING_REPLY)
        repeat(40) { // 60 s of 1.5 s ticks, as the service's widget tick runs them
            now += 1_500L
            publisher.heartbeat(scope, VoiceRequestService.HEARTBEAT_MS)
            assertTrue(now - publisher.read().sourceAtMillis <= OptionalOverlayController.SOURCE_TTL_MS)
        }
    }

    @Test fun heartbeatNeverRevivesAStoppedOrForeignSession() {
        val scope = publisher.start(1, ServiceUiPublication.Mode.MANUAL)
        val foreign = ServiceUiPublication.Scope(99, null)
        now += 20_000L
        assertFalse(publisher.heartbeat(foreign, VoiceRequestService.HEARTBEAT_MS))
        publisher.phase(scope, ServiceUiPublication.Phase.STOPPING)
        val stopped = publisher.read()
        now += 20_000L
        assertFalse(publisher.heartbeat(scope, VoiceRequestService.HEARTBEAT_MS))
        assertSame(stopped, publisher.read())
    }
}
