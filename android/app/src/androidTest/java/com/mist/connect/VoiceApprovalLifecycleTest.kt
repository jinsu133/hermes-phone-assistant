package com.mist.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Harmless callback fixtures only: never grants permission for a real phone action. */
@RunWith(AndroidJUnit4::class)
class VoiceApprovalLifecycleTest {
    @Test fun voiceWaitHasNoApprovalActivityAndCannotUseButtonDecision() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=instrumentation.addMonitor(ActionConfirmationActivity::class.java.name,null,false)
        val executions=AtomicInteger()
        lateinit var receipt: JSONObject
        try {
            instrumentation.runOnMainSync {
                ActionConfirmation.cancelAll()
                receipt=ActionConfirmation.request("테스트 전용 작업", "fixture-voice", "이 작업 실행 허용", "a".repeat(64),
                    { true }, { executions.incrementAndGet(); JSONObject().put("dispatched",true) })
            }
            instrumentation.waitForIdleSync()
            assertEquals("awaiting_voice_confirmation",receipt.getString("state"))
            assertEquals(0,monitor.hits)
            assertNull(ActionConfirmation.visible)
            val id=receipt.getString("request_id")
            instrumentation.runOnMainSync { ActionConfirmation.decide(id,true) }
            instrumentation.waitForIdleSync()
            assertEquals(0,executions.get())
            assertEquals("awaiting_voice_confirmation",ActionConfirmation.status(id).getString("state"))
            instrumentation.runOnMainSync { ActionConfirmation.cancelAll() }
            assertEquals("cancelled",ActionConfirmation.status(id).getString("state"))
            assertEquals(0,executions.get())
        } finally {
            instrumentation.runOnMainSync { ActionConfirmation.cancelAll() }
            instrumentation.removeMonitor(monitor)
        }
    }
    @Test fun wrongVoiceOwnerCannotExecuteFixture() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val executions=AtomicInteger()
        instrumentation.runOnMainSync {
            ActionConfirmation.cancelAll()
            val receipt=ActionConfirmation.request("테스트 전용", "not-active-request", "이 작업 실행 허용", "b".repeat(64),
                { true }, { executions.incrementAndGet(); JSONObject().put("dispatched",true) })
            val p=ActionConfirmation.get(receipt.getString("request_id"))!!
            ActionConfirmation.decideVoice(p,JSONObject().put("request_id",p.voiceRequest).put("action_id",p.id).put("digest",p.digest).put("allowed",true))
            assertEquals(0,executions.get())
            ActionConfirmation.cancelAll()
        }
    }
}
