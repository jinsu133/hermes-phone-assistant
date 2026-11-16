package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

class CaptureEndTest {
    @Test fun exceptionsMapToOneReasonEach() {
        assertEquals(CaptureEnd.SAFETY_LIMIT, CaptureEnd.of(SpeechEndGate.Rejected(SpeechEndGate.Decision.SAFETY_LIMIT)))
        assertEquals(CaptureEnd.AMBIGUOUS, CaptureEnd.of(SpeechEndGate.Rejected(SpeechEndGate.Decision.AMBIGUOUS)))
        assertEquals(CaptureEnd.MIC_READ_FAILED, CaptureEnd.of(IllegalStateException("mic_read_failed")))
        assertEquals(CaptureEnd.ERROR, CaptureEnd.of(IllegalStateException("Check failed.")))
        assertEquals(CaptureEnd.ERROR, CaptureEnd.of(SecurityException("mic")))
    }

    @Test fun inactiveSplitsWaitingFromAfterWake() {
        assertEquals(CaptureEnd.INACTIVE_AFTER_WAKE, CaptureEnd.inactive(true))
        assertEquals(CaptureEnd.INACTIVE_WAITING, CaptureEnd.inactive(false))
        assertTrue(CaptureEnd.INACTIVE_AFTER_WAKE.afterWakeWithoutUpload)
        assertFalse(CaptureEnd.INACTIVE_WAITING.afterWakeWithoutUpload)
    }

    @Test fun quietFollowUpIsNotReportedAsAFailure() {
        assertEquals(CaptureEnd.FOLLOWUP_QUIET, CaptureEnd.settle(CaptureEnd.NO_SPEECH, followup = true, triggered = true))
        assertEquals(CaptureEnd.FOLLOWUP_QUIET, CaptureEnd.settle(null, followup = true, triggered = true))
        assertFalse(CaptureEnd.FOLLOWUP_QUIET.afterWakeWithoutUpload)
        assertEquals(CaptureEnd.NO_SPEECH, CaptureEnd.settle(CaptureEnd.NO_SPEECH, followup = false, triggered = true))
        assertEquals(CaptureEnd.AMBIGUOUS, CaptureEnd.settle(CaptureEnd.AMBIGUOUS, followup = true, triggered = true))
        assertEquals(CaptureEnd.INACTIVE_AFTER_WAKE, CaptureEnd.settle(null, followup = false, triggered = true))
        assertEquals(CaptureEnd.INACTIVE_WAITING, CaptureEnd.settle(null, followup = false, triggered = false))
    }

    @Test fun codesAreUniqueAndSafe() {
        val codes = CaptureEnd.values().map { it.code } + TrainEnd.values().map { it.code }
        assertEquals(CaptureEnd.values().size, CaptureEnd.values().map { it.code }.toSet().size)
        assertEquals(TrainEnd.values().size, TrainEnd.values().map { it.code }.toSet().size)
        assertTrue(codes.all { it.matches(Regex("[a-z_]+")) })
    }

    @Test fun abnormalAfterWakeEndIsShownOnceAndCompleteClearsIt() {
        val c = EndCounters()
        c.captureEnded(CaptureEnd.NO_SPEECH)
        assertEquals(CaptureEnd.NO_SPEECH, c.takeUnshown())
        assertNull(c.takeUnshown())

        c.captureEnded(CaptureEnd.INACTIVE_AFTER_WAKE)
        c.captureEnded(CaptureEnd.INACTIVE_WAITING) // normal OFF keeps the pending notice
        assertEquals(CaptureEnd.INACTIVE_AFTER_WAKE, c.takeUnshown())

        c.captureEnded(CaptureEnd.AMBIGUOUS)
        c.captureEnded(CaptureEnd.COMPLETE) // a later upload makes the old notice stale
        assertNull(c.takeUnshown())
        assertEquals(CaptureEnd.COMPLETE, c.lastCapture())
    }

    @Test fun countsEveryReason() {
        val c = EndCounters()
        repeat(2) { c.captureEnded(CaptureEnd.NO_SPEECH) }
        c.captureEnded(CaptureEnd.MIC_READ_FAILED)
        c.trainEnded(TrainEnd.TOO_FEW_TOKENS)
        val counts = c.captureCounts()
        assertEquals(2L, counts["no_speech"]); assertEquals(1L, counts["mic_read_failed"])
        assertEquals(0L, counts["complete"]); assertEquals(CaptureEnd.values().size, counts.size)
        assertEquals(1L, c.trainCounts()["too_few_tokens"]); assertEquals(TrainEnd.TOO_FEW_TOKENS, c.lastTrain())
    }

    @Test fun tokenRejectionNamesTheRule() {
        val vocab = setOf("▁HE", "Y", "▁HER", "ME", "S", "X")
        assertEquals(TrainEnd.TOO_FEW_TOKENS, KwsTokens.rejection(listOf("▁HE", "Y"), 3, vocab))
        assertEquals(TrainEnd.TOO_FEW_TOKENS, KwsTokens.rejection(listOf("▁HE", "unknown", "Y"), 3, vocab))
        assertEquals(TrainEnd.NO_WORD_START, KwsTokens.rejection(listOf("Y", "▁HER", "ME"), 3, vocab))
        assertEquals(TrainEnd.TOO_MANY_TOKENS, KwsTokens.rejection(listOf("▁HE") + List(24) { "X" }, 3, vocab))
        assertNull(KwsTokens.rejection(listOf("▁HE", "Y", "▁HER", "ME", "S"), 3, vocab))
        assertEquals("▁HE Y ▁HER ME S", KwsTokens.learnedLine(listOf("▁HE", "Y", "▁HER", "ME", "S"), 3, vocab))
        assertNull(KwsTokens.learnedLine(listOf("▁HE", "Y"), 3, vocab))
        assertEquals(2, KwsTokens.kept(listOf("▁HE", " ", "nope", "Y"), vocab).size)
    }
}
