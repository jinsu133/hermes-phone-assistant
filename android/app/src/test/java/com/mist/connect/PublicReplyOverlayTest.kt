package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

/** In-memory publisher -> real observation store -> real overlay controller; no Android window. */
class PublicReplyOverlayTest {
    private class Window : OptionalOverlayController.Window {
        var text: String? = null
        override fun show(title: String): Boolean { text = title; return true }
        override fun hide() { text = null }
    }
    @Test fun displaysMatchedPublicFinalAndHidesOnOff() {
        val now = 100L
        val publisher = ServiceUiPublication(Any()) { now }
        val session = publisher.start(1, ServiceUiPublication.Mode.HANDSFREE)
        val request = publisher.beginRequest(session, "operation")
        publisher.acceptRequest(request, "request")
        val store = UiObservationStore("repo") { }
        store.rebind(publisher, publisher.read(), LocalUiFacts(now))
        val window = Window()
        val controller = OptionalOverlayController(window) { now }
        val token = controller.attach("repo", null)
        controller.configure(true, true, true)
        publisher.publishFinal(request, "request", "A real public reply")
        store.refresh(publisher, publisher.read(), LocalUiFacts(now))
        controller.offer(token, store.read())
        assertEquals("A real public reply", window.text)
        publisher.off(session, true)
        store.refresh(publisher, publisher.read(), LocalUiFacts(now))
        controller.offer(token, store.read())
        assertNull(window.text)
    }

    @Test fun screenOffHidesAndUnlockDoesNotRestoreOldAnswer() {
        val now = 100L
        val publisher = ServiceUiPublication(Any()) { now }
        val session = publisher.start(1, ServiceUiPublication.Mode.HANDSFREE)
        val request = publisher.beginRequest(session, "operation")
        publisher.acceptRequest(request, "request")
        val store = UiObservationStore("repo") { }
        store.rebind(publisher, publisher.read(), LocalUiFacts(now))
        val window = Window()
        val controller = OptionalOverlayController(window) { now }
        val token = controller.attach("repo", null)
        controller.configure(true, true, true)
        publisher.publishFinal(request, "request", "Private public answer")
        store.refresh(publisher, publisher.read(), LocalUiFacts(now))
        controller.offer(token, store.read())
        assertEquals("Private public answer", window.text)
        controller.configure(true, true, false)
        assertNull(window.text)
        controller.configure(true, true, true)
        store.refresh(publisher, publisher.read(), LocalUiFacts(now))
        controller.offer(token, store.read())
        assertNotEquals("Private public answer", window.text)
    }
}
