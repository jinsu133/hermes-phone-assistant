package com.mist.connect

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class OverlayMotionContractTest {
    @Test fun actualVoicePhaseReachesTheVisualWindow() {
        val phases = mutableListOf<UiPhase>()
        val window = Proxy.newProxyInstance(
            OptionalOverlayController.Window::class.java.classLoader,
            arrayOf(OptionalOverlayController.Window::class.java)
        ) { _, method, args ->
            when (method.name) {
                "show" -> true
                "visualPhase" -> { phases.add(args!![0] as UiPhase); null }
                else -> null
            }
        } as OptionalOverlayController.Window
        val now = 100L
        val publisher = ServiceUiPublication(Any()) { now }
        val session = publisher.start(1, ServiceUiPublication.Mode.HANDSFREE)
        val store = UiObservationStore("repo") { }
        store.rebind(publisher, publisher.read(), LocalUiFacts(now))
        val controller = OptionalOverlayController(window) { now }
        val token = controller.attach("repo", null)
        controller.configure(true, true, true)
        for (phase in listOf(ServiceUiPublication.Phase.CAPTURING,
                             ServiceUiPublication.Phase.WAITING_REPLY,
                             ServiceUiPublication.Phase.PLAYING)) {
            publisher.phase(session, phase)
            store.refresh(publisher, publisher.read(), LocalUiFacts(now))
            controller.offer(token, store.read())
        }
        assertEquals(listOf(UiPhase.CAPTURING, UiPhase.WAITING_REPLY, UiPhase.PLAYING), phases)
    }
}
