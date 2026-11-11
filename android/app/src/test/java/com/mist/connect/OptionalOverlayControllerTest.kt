package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

/** PREPARED ONLY. Real M05 publisher/store/presenter, inert clock/window; no Android runtime. */
class OptionalOverlayControllerTest {
    private class Window : OptionalOverlayController.Window {
        var text: String? = null
        var adds = 0
        var maxWindows = 0
        var liveWindows = 0
        val deviceActionCount = 0 // Window has no device/action capability.
        override fun show(title: String): Boolean {
            if (text == null) { adds++; liveWindows++ }
            text = title; maxWindows = maxOf(maxWindows, liveWindows); return true
        }
        override fun hide() { if (text != null) liveWindows--; text = null }
    }
    private class Rig {
        var now = 100L
        val window = Window()
        val queued = ArrayDeque<Runnable>()
        val store = UiObservationStore("repo") { queued.add(it) }
        var publisher = ServiceUiPublication(Any()) { now }
        var scope = publisher.start(1, ServiceUiPublication.Mode.HANDSFREE)
        val controller = OptionalOverlayController(window) { now }
        val token = controller.attach("repo", null)
        var sequence = 0L
        init { store.rebind(publisher, publisher.read(), LocalUiFacts(now)); controller.configure(true, true, true) }
        fun state(): CompanionUiState {
            store.refresh(publisher, publisher.read(), LocalUiFacts(now))
            return store.read()
        }
        fun emit() { controller.offer(token, state()) }
        fun phase() { publisher.phase(scope, ServiceUiPublication.Phase.WAITING_REPLY); emit() }
    }
    @Test fun consentDefaultsOffDespitePermission() {
        val r = Rig(); r.controller.configure(false, true, true); r.emit()
        assertNull(r.window.text); assertEquals(0, r.window.adds)
    }
    @Test fun deniedPermissionDoesNotCreateWindowOrAction() {
        val r = Rig(); r.controller.configure(true, false, true); r.emit()
        assertNull(r.window.text); assertEquals(0, r.window.deviceActionCount)
        r.controller.configure(true, true, true); r.emit(); assertNotNull(r.window.text)
    }
    @Test fun staleProducerCannotBeRejuvenatedByPolling() {
        val r = Rig(); r.emit(); assertNotNull(r.window.text)
        r.now += OptionalOverlayController.SOURCE_TTL_MS + 1; r.emit()
        assertNull(r.window.text)
    }
    @Test fun stalledObservationExpiresOnWatchdog() {
        val r = Rig(); r.emit(); r.now += OptionalOverlayController.OBSERVATION_TTL_MS + 1
        r.controller.refresh(); assertNull(r.window.text)
    }
    @Test fun olderSequenceCannotOverwriteNewerPhase() {
        val r = Rig(); val old = r.state(); r.phase(); val title = r.window.text
        r.controller.offer(r.token, old); assertEquals(title, r.window.text)
    }
    @Test fun newerGenerationAfterOffIsAcceptedButOldGenerationIsNot() {
        val r = Rig(); val old = r.state(); r.controller.offRequested(r.token, old); r.emit()
        assertNull(r.window.text)
        r.scope = r.publisher.start(2, ServiceUiPublication.Mode.HANDSFREE); r.emit()
        assertNotNull(r.window.text)
        r.controller.offer(r.token, old.copy(sequence = r.state().sequence + 1))
        assertEquals(1, r.window.maxWindows)
        assertNotNull(r.window.text)
    }
    @Test fun pendingOffWithUnchangedObservationSequenceHidesImmediately() {
        val r = Rig(); val active = r.state(); r.controller.offer(r.token, active)
        r.store.offRequested(); r.controller.offer(r.token, r.store.read())
        assertNull(r.window.text)
        r.controller.offer(r.token, active); assertNull(r.window.text)
    }
    @Test fun publishedRevocationHidesEvenIfCleanupRemainsPending() {
        val r = Rig(); r.emit(); r.publisher.off(r.scope, true); r.emit()
        assertNull(r.window.text)
    }
    @Test fun detachAndLateCallbackCannotReopenWindow() {
        val r = Rig(); val active = r.state(); r.controller.offer(r.token, active)
        r.controller.detach(r.token); r.controller.offer(r.token, active)
        assertNull(r.window.text)
    }
    @Test fun restartCannotReplayRetainedServiceSnapshot() {
        val r = Rig(); val old = r.state(); r.controller.detach(r.token)
        val freshToken = r.controller.attach("repo", old.owner)
        r.controller.offer(freshToken, old); assertNull(r.window.text)
        r.publisher = ServiceUiPublication(Any()) { r.now }
        r.scope = r.publisher.start(1, ServiceUiPublication.Mode.HANDSFREE)
        r.store.rebind(r.publisher, r.publisher.read(), LocalUiFacts(r.now))
        r.controller.offer(freshToken, r.state()); assertNotNull(r.window.text)
        r.controller.offer(r.token, old); assertNotNull(r.window.text)
    }
    @Test fun repeatedRenderAndRotationUseOneWindow() {
        val r = Rig(); repeat(25) { r.emit(); r.controller.refresh() }
        assertEquals(1, r.window.adds); assertEquals(1, r.window.maxWindows)
        // The adapter's rotation policy is hide then refresh, not Activity reattach.
        repeat(5) {
            r.controller.configure(true, true, false)
            r.controller.configure(true, true, true); r.emit()
        }
        assertEquals(1, r.window.liveWindows); assertEquals(1, r.window.maxWindows)
        assertEquals(0, r.window.deviceActionCount)
    }
    @Test fun realStoreSubscriptionDetachRejectsQueuedAndAlreadyCapturedCallback() {
        val r = Rig()
        val listener: (CompanionUiState) -> Unit = { r.controller.offer(r.token, it) }
        val subscription = r.store.subscribe(listener)
        assertEquals(1, r.store.subscriberCount())
        while (r.queued.isNotEmpty()) r.queued.removeFirst().run()
        assertNotNull(r.window.text)
        val late = r.state()
        r.controller.detach(r.token); subscription.close()
        while (r.queued.isNotEmpty()) r.queued.removeFirst().run()
        listener(late)
        assertEquals(0, r.store.subscriberCount()); assertEquals(0, r.window.liveWindows)
    }
    @Test fun lockApprovalUnlockAndScreenOffHide() {
        val r = Rig(); r.emit()
        r.controller.offer(r.token, r.state().copy(locked = true)); assertNull(r.window.text)
        r.publisher.waitFor(r.scope, ServiceUiPublication.Wait.APPROVAL); r.emit(); assertNull(r.window.text)
        r.publisher.waitFor(r.scope, ServiceUiPublication.Wait.UNLOCK); r.emit(); assertNull(r.window.text)
        r.publisher.waitFor(r.scope, ServiceUiPublication.Wait.NONE); r.emit(); assertNotNull(r.window.text)
        r.controller.configure(true, true, false); assertNull(r.window.text)
    }
    @Test fun disablingConsentFencesLateDeliveryAndDoesNotChangePublisher() {
        val r = Rig(); r.emit(); val source = r.publisher.read()
        r.controller.configure(false, true, true); r.emit()
        assertNull(r.window.text); assertSame(source, r.publisher.read())
    }
    @Test fun futureTimeAndWrongRepositoryEpochFailClosed() {
        val r = Rig(); val s = r.state()
        r.controller.offer(r.token, s.copy(observationEpoch = "old-process")); assertNull(r.window.text)
        r.controller.offer(r.token, s.copy(observedAtMillis = r.now + 1)); assertNull(r.window.text)
    }
    @Test fun lateOldOffCannotDowngradeNewGenerationFence() {
        val r = Rig(); val old = r.state(); r.controller.offer(r.token, old)
        r.scope = r.publisher.start(2, ServiceUiPublication.Mode.HANDSFREE)
        val current = r.state(); r.controller.offer(r.token, current)
        r.controller.offRequested(r.token, current)
        r.controller.offRequested(r.token, old)
        r.emit(); assertNull(r.window.text)
    }
    @Test fun windowFailureDoesNotEscapeIntoVoiceAndDoesNotRetryUntilReattach() {
        val w = object : OptionalOverlayController.Window {
            var calls = 0
            override fun show(title: String): Boolean { calls++; throw SecurityException("fixture") }
            override fun hide() {}
        }
        val r = Rig(); val c = OptionalOverlayController(w) { r.now }
        val token = c.attach("repo", null); c.configure(true, true, true)
        c.offer(token, r.state()); c.offer(token, r.state())
        assertEquals(1, w.calls)
    }
}
