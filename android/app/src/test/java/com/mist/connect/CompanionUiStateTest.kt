package com.mist.connect

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PREPARED, NOT EXECUTED. Actual publication -> actual repository core -> reducer ->
 * exact shared home/setup/widget text. No Android, microphone, network or model effects.
 * Clock/scheduler/sink are inert; production state logic is not reimplemented. */
class CompanionUiStateTest {
    private class Rig {
        var now = 1_000L
        val gate = Any()
        val publisher = ServiceUiPublication(gate) { now }
        val queue = ArrayDeque<Runnable>()
        val store = UiObservationStore("ui-process") { synchronized(queue) { queue.add(it) } }
        var local = LocalUiFacts(now, keysPresent = true, microphoneGranted = true)
        fun read() {
            local = local.copy(now = now)
            store.refresh(publisher, publisher.read(), local)
        }
        fun bind() = store.rebind(publisher, publisher.read(), local)
        fun start(id: Int = 1): ServiceUiPublication.Scope {
            val s = synchronized(gate) { publisher.start(id, ServiceUiPublication.Mode.HANDSFREE) }
            bind(); read(); return s
        }
        fun drain() {
            repeat(100) {
                val task = synchronized(queue) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return
                task.run()
            }
            error("unbounded_display_delivery")
        }
    }
    @Test fun noProducerIsUnknownNeverConfirmedOff() {
        val r = Rig()
        r.store.refresh(null, null, r.local)
        assertEquals(UiListening.UNKNOWN, r.store.read().listening)
        assertTrue(CompanionUiText.title(r.store.read()).contains("확인되지 않음"))
        assertFalse(CompanionUiText.title(r.store.read()).contains("OFF"))
    }
    @Test fun sameStampLocalLockPermissionReadinessAlwaysRefresh() {
        val r = Rig(); r.start(); val stamp = r.store.read().owner
        r.local = r.local.copy(locked = true, microphoneGranted = false,
            readiness = SetupReadiness.Phase.CHECKING, readinessAt = r.now)
        r.read()
        assertEquals(stamp, r.store.read().owner)
        assertTrue(r.store.read().locked); assertFalse(r.store.read().microphoneGranted)
        assertEquals(SetupReadiness.Phase.CHECKING, r.store.read().readiness)
        r.local = r.local.copy(readiness = SetupReadiness.Phase.AVAILABLE)
        r.read(); assertEquals(SetupReadiness.Phase.AVAILABLE, r.store.read().readiness)
    }
    @Test fun staleServiceCannotDiscardFreshLocalFactsOrRefreshServiceReceiptTime() {
        val r = Rig(); val s = r.start(); val stale = r.publisher.read()
        r.now += 10; r.publisher.captureEvent(s, ServiceUiPublication.CaptureEvent.WAKE_WAIT); r.read()
        val current = r.store.read()
        r.now += 10
        r.store.refresh(r.publisher, stale, r.local.copy(now = r.now, locked = true))
        assertSame(current.service, r.store.read().service)
        assertTrue(r.store.read().locked)
        assertEquals(current.serviceReceivedAtMillis, r.store.read().serviceReceivedAtMillis)
    }
    @Test fun fullOffRejectsLateCaptureAcceptPlaybackBeforeRepository() {
        val r = Rig(); val s = r.start()
        val request = r.publisher.beginRequest(s, "operation")
        r.publisher.acceptRequest(request, "request")
        r.publisher.off(s, true); val off = r.publisher.read()
        r.publisher.captureEvent(s, ServiceUiPublication.CaptureEvent.CAPTURING)
        r.publisher.acceptRequest(request, "late-request")
        r.publisher.playback(request, ServiceUiPublication.Playback.ACTIVE, ServiceUiPublication.Cleanup.PENDING)
        assertSame(off, r.publisher.read())
        r.read(); assertEquals(UiPhase.STOPPING, r.store.read().phase)
        assertNull(r.store.read().requestId)
    }
    @Test fun offFenceAllowsConflatedOffThenAcceptedOn() {
        val r = Rig(); val old = r.start(4)
        r.store.offRequested(); r.publisher.off(old, true)
        // Deliberately do not deliver OFF, just as latest-slot/StateFlow conflation may do.
        r.publisher.start(5, ServiceUiPublication.Mode.HANDSFREE)
        r.read()
        assertFalse(r.store.read().offPending)
        assertEquals(5L, r.store.read().owner!!.generation)
        assertEquals(UiPhase.PREPARING, r.store.read().phase)
        val latest = r.publisher.read()
        r.publisher.captureEvent(old, ServiceUiPublication.CaptureEvent.WAKE_WAIT)
        assertSame(latest, r.publisher.read())
    }
    @Test fun offFenceDoesNotInventResourceClosure() {
        val r = Rig(); val s = r.start()
        r.publisher.captureEvent(s, ServiceUiPublication.CaptureEvent.MONITOR)
        r.publisher.playback(s, ServiceUiPublication.Playback.ACTIVE, ServiceUiPublication.Cleanup.PENDING)
        r.read(); r.store.offRequested(); r.read()
        assertTrue(r.store.read().offPending)
        assertEquals(ServiceUiPublication.Capture.MONITOR, r.store.read().service!!.capture)
        assertEquals(UiPlayback.PLAYING, r.store.read().playback)
        assertFalse(CompanionUiText.title(r.store.read()).contains("정리 확인"))
    }
    @Test fun explicitReadOnlyRebindRejectsRetiredChannelWithoutConsentRestore() {
        val r = Rig(); val old = r.start()
        val replacement = ServiceUiPublication(r.gate) { r.now }
        assertEquals(r.publisher.read().processEpoch, replacement.read().processEpoch)
        assertNotEquals(r.publisher.read().incarnation, replacement.read().incarnation)
        r.store.rebind(replacement, replacement.read(), r.local)
        r.publisher.captureEvent(old, ServiceUiPublication.CaptureEvent.CAPTURING)
        r.store.refresh(r.publisher, r.publisher.read(), r.local.copy(locked = true))
        assertSame(replacement.read(), r.store.read().service)
        assertEquals(UiListening.UNKNOWN, r.store.read().listening)
        assertTrue(r.store.read().locked)
        assertFalse(replacement.read().acceptedStart)
    }
    @Test fun processRestartBeginsUnobservedNotSavedOn() {
        val r = Rig(); r.start()
        val fresh = UiObservationStore("new-ui-process") { error("no_subscriber") }
        assertNull(fresh.read().service)
        assertEquals(UiListening.UNKNOWN, fresh.read().listening)
    }
    @Test fun monitorAndPlaybackAxesAreIndependentAndAllPhaseWordsReachSharedText() {
        val r = Rig(); val s = r.start()
        ServiceUiPublication.CaptureEvent.values().forEach { r.publisher.captureEvent(s, it); r.read() }
        r.publisher.captureEvent(s, ServiceUiPublication.CaptureEvent.MONITOR)
        r.publisher.playback(s, ServiceUiPublication.Playback.ACTIVE, ServiceUiPublication.Cleanup.PENDING)
        r.read()
        assertEquals(ServiceUiPublication.Capture.MONITOR, r.store.read().service!!.capture)
        assertEquals(UiPlayback.PLAYING, r.store.read().playback)
        assertTrue(CompanionUiText.details(r.store.read()).contains("MONITOR"))
        listOf(ServiceUiPublication.Phase.PREPARING, ServiceUiPublication.Phase.WAKE_WAIT,
            ServiceUiPublication.Phase.CAPTURING, ServiceUiPublication.Phase.SUBMITTING,
            ServiceUiPublication.Phase.WAITING_REPLY, ServiceUiPublication.Phase.FOLLOW_UP).forEach {
            r.publisher.phase(s, it); r.read()
            assertEquals(it.name, r.store.read().phase.name)
            assertEquals(CompanionUiText.phase(r.store.read().phase), CompanionUiText.title(r.store.read()))
        }
    }
    @Test fun requestRetirementRejectsOldAcceptanceButKeepsSessionUsable() {
        val r = Rig(); val s = r.start()
        val a = r.publisher.beginRequest(s, "op-a"); r.publisher.acceptRequest(a, "req-a")
        r.publisher.retireRequest(a, true)
        val b = r.publisher.beginRequest(s, "op-b"); r.publisher.acceptRequest(b, "req-b")
        r.publisher.acceptRequest(a, "old")
        r.publisher.remote(a, ServiceUiPublication.Cleanup.CONFIRMED, true)
        r.read()
        assertEquals("req-b", r.store.read().requestId)
        assertEquals(ServiceUiPublication.Cleanup.PENDING, r.store.read().service!!.remoteCleanup)
    }
    @Test fun confirmationIsWaitOnlyAndDoesNotExposeApprovalFields() {
        val r = Rig(); val s = r.start()
        val req = r.publisher.beginRequest(s, "operation"); r.publisher.acceptRequest(req, "request")
        r.publisher.waitFor(req, ServiceUiPublication.Wait.APPROVAL); r.read()
        assertTrue(CompanionUiText.title(r.store.read()).contains("기존 확인 화면"))
        assertEquals(ServiceUiPublication.Wait.APPROVAL, r.store.read().service!!.waiting)
        assertFalse(HomeCommand.entries.any { it.name == "APPROVE" })
    }
    @Test fun cleanupUncertaintyStickyAcrossOffDestroyAndLaterSuccess() {
        val r = Rig(); val s = r.start()
        r.publisher.captureClosed(s, false); r.publisher.off(s, true)
        r.publisher.captureClosed(s, true); r.publisher.settle(s, true, true, true, false)
        r.publisher.off(s, true); r.read()
        assertEquals(UiPhase.BLOCKED, r.store.read().phase)
        assertEquals(ServiceUiPublication.Cleanup.UNCERTAIN, r.store.read().service!!.captureCleanup)
    }
    @Test fun remoteAckWithoutActualJoinCannotBecomeConfirmedOff() {
        val r = Rig(); val s = r.start()
        r.publisher.off(s, true)
        r.publisher.settle(s, false, true, true, false); r.read() // lease release not yet confirmed
        assertEquals(UiPhase.STOPPING, r.store.read().phase)
        r.publisher.settle(s, true, true, false, false); r.read()
        assertEquals(UiPhase.STOPPING, r.store.read().phase)
        r.publisher.settle(s, true, true, true, false); r.read()
        assertEquals(UiPhase.OFF_CONFIRMED, r.store.read().phase)
        val complete = r.publisher.read(); r.publisher.off(s, true)
        assertSame(complete, r.publisher.read())
    }
    @Test fun subscriptionsConflateAndDeliverOutsideGateOnScheduledThread() {
        val r = Rig(); val s = r.start(); val seen = mutableListOf<String>()
        val home = r.store.subscribe { assertFalse(Thread.holdsLock(r.gate)); seen.add("home:" + Thread.currentThread().name) }
        val setup = r.store.subscribe { seen.add("setup:" + CompanionUiText.title(it)) }
        val widget = r.store.subscribe { seen.add("widget:" + CompanionUiText.title(it)) }
        val producer = Thread {
            repeat(200) { synchronized(r.gate) { r.publisher.phase(s, ServiceUiPublication.Phase.WAITING_REPLY) }; r.read() }
        }
        producer.start(); producer.join(3000); assertFalse(producer.isAlive)
        assertTrue(seen.isEmpty()); assertEquals(1, r.queue.size)
        val deliveryThread = Thread.currentThread().name
        r.drain(); assertEquals(3, seen.size); assertEquals("home:$deliveryThread", seen.first())
        assertEquals(seen[1].substringAfter(":"), seen[2].substringAfter(":"))
        home.close(); home.close(); setup.close(); widget.close()
        assertEquals(0, r.store.subscriberCount())
        r.read(); assertTrue(r.queue.isEmpty())
    }
    @Test fun detachedSubscribersAndReadinessExpiryWhileOff() {
        val r = Rig(); var renders = 0
        val stoppedActivity = r.store.subscribe { fail("stopped_activity_delivery") }
        stoppedActivity.close()
        val widget = r.store.subscribe { renders++; assertFalse(CompanionUiText.title(it).isEmpty()) }
        r.local = r.local.copy(readiness = SetupReadiness.Phase.AVAILABLE, readinessAt = r.now)
        r.read(); r.drain(); assertEquals(SetupReadiness.Phase.AVAILABLE, r.store.read().readiness)
        r.now += 30_000L; r.read(); r.drain()
        assertEquals(SetupReadiness.Phase.UNCHECKED, r.store.read().readiness)
        assertEquals(2, renders); widget.close()
    }
    @Test fun renderingRebindAndRefreshHaveZeroCommandsWhileGestureDispatchesExactlyOnce() {
        val r = Rig(); var effects = 0; val router = HomeCommandRouter()
        HomeCommand.entries.forEach { router.bind(it) { effects++ } }
        r.store.subscribe { CompanionUiText.title(it); CompanionUiText.details(it) }
        r.start(); repeat(10) { r.read(); r.drain() }
        assertEquals(0, effects)
        HomeCommand.entries.forEach { router.dispatch(it) }
        assertEquals(HomeCommand.entries.size, effects)
        assertFalse(HomeCommand.entries.any { it.name == "APPROVE" })
    }
    @Test fun equalNumbersFromDifferentProducerCannotWinWithoutExplicitRebind() {
        val r = Rig(); r.start()
        val other = ServiceUiPublication(r.gate) { r.now }
        other.start(1, ServiceUiPublication.Mode.HANDSFREE)
        assertEquals(r.publisher.read().generation, other.read().generation)
        assertEquals(r.publisher.read().sequence, other.read().sequence)
        r.store.refresh(other, other.read(), r.local.copy(locked = true))
        assertSame(r.publisher.read(), r.store.read().service)
        assertTrue(r.store.read().locked)
        r.store.rebind(other, other.read(), r.local)
        assertSame(other.read(), r.store.read().service)
    }
    @Test fun subscriberBoundAndOneConsumerFailureDoNotStarveOtherViews() {
        val r = Rig(); var delivered = 0
        val subscriptions = (0 until 8).map { index -> r.store.subscribe {
            if (index == 0) throw IllegalStateException("fixture_display_failure") else delivered++
        } }
        try {
            try { r.store.subscribe {}; fail("subscriber_bound_missing") } catch (_: IllegalStateException) { }
            r.read(); r.drain(); assertEquals(7, delivered)
        } finally { subscriptions.forEach { it.close() } }
        assertEquals(0, r.store.subscriberCount())
    }
    @Test fun realOwnerStartGatePublishesOnlyAfterInertSinkStartAndActualJoin() {
        val r = Rig(); val scope = r.start()
        val owner = SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE) { r.now }
        owner.bindUiPublication(r.publisher, scope)
        assertTrue(owner.begin(1, 1))
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val session = object : SourceServicePlaybackAdapter.Session {
            var closed = false
            override fun play(): Boolean = error("must_use_actual_owner_start_gate")
            override fun playGuarded(gate: SourceServicePlaybackAdapter.StartGate): Boolean {
                if (!gate.start { started.countDown() }) return false
                return release.await(3, TimeUnit.SECONDS)
            }
            override fun abort() { release.countDown() }
            override fun close() { closed = true }
            override fun sourceClosed() = closed
            override fun sinkClosed() = closed
        }
        assertTrue(owner.enqueue(1, 0, "fixture", SourceServicePlaybackAdapter.Kind.FINAL) { session })
        assertEquals(ServiceUiPublication.Playback.IDLE, r.publisher.read().playback)
        try {
            assertTrue(owner.startNext()); assertTrue(started.await(3, TimeUnit.SECONDS))
            // Start gate commits before releasing owner lock; getter synchronizes that boundary.
            assertFalse(owner.readUiOwnedState().rendererJoined)
            r.read(); assertEquals(UiPlayback.PLAYING, r.store.read().playback)
        } finally { release.countDown(); assertTrue(owner.awaitWorker(3000)) }
        r.read(); assertEquals(UiPlayback.IDLE, r.store.read().playback)
        assertEquals(ServiceUiPublication.Cleanup.CONFIRMED, r.store.read().service!!.playbackCleanup)
    }
}
