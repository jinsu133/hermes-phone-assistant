package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

class WakeSettingsTest {
    private val vocab = setOf("▁", "▁HE", "Y", "▁HER", "ME", "S", "▁STOP", "▁CAN", "CE", "L", "▁JAR", "VI", "▁A")

    @Test fun middleSensitivityKeepsShippedTuning() {
        assertEquals(0.10f, WakeSettings.threshold(50), 1e-6f)
        assertEquals(1.5f, WakeSettings.score(50), 1e-6f)
    }

    @Test fun higherSensitivityIsEasierToTrigger() {
        for (s in 0 until 100) {
            assertTrue(WakeSettings.threshold(s + 1) <= WakeSettings.threshold(s))
            assertTrue(WakeSettings.score(s + 1) >= WakeSettings.score(s))
        }
        assertTrue(WakeSettings.threshold(100) > 0f)
        assertEquals(WakeSettings.threshold(0), WakeSettings.threshold(-5), 0f)
    }

    @Test fun keywordSpecUsesRootLocaleAndLabel() {
        val spec = WakeSettings.keywordSpec(listOf("▁STOP"), "STOP", 50)
        assertEquals("▁STOP :1.50 #0.100 @STOP", spec)
    }

    @Test fun encodesLatinPhrasesWithModelPieces() {
        assertEquals(listOf("▁HE", "Y", "▁HER", "ME", "S"), KwsTokens.encode("Hey Hermes", vocab))
        assertEquals(listOf("▁STOP"), KwsTokens.encode("stop!", vocab))
        assertEquals(listOf("▁CAN", "CE", "L"), KwsTokens.encode("Cáncel", vocab))
    }

    @Test fun nonLatinPhrasesNeedVoiceTraining() {
        assertNull(KwsTokens.encode("헤이 자비스", vocab))
        assertNull(KwsTokens.encode("ストップ", vocab))
        assertNull(KwsTokens.encode("  ", vocab))
    }

    @Test fun learnedLinesMustBeDistinctiveAndInVocabulary() {
        assertEquals("▁JAR VI S", KwsTokens.learnedLine(listOf("▁JAR", "VI", "S"), 3, vocab))
        assertNull(KwsTokens.learnedLine(listOf("▁A"), 3, vocab))
        assertNull(KwsTokens.learnedLine(listOf("VI", "S", "▁A"), 1, vocab))
        assertNull(KwsTokens.learnedLine(listOf("<unk>", "▁A"), 2, vocab))
        assertEquals("JARVIS", WakeSettings.readable("▁JAR VI S"))
    }

    @Test fun stopUtteranceLimitGrowsWithLengthAndIsCapped() {
        assertEquals(1500, WakeSettings.maxStopMillis("Stop"))
        assertEquals(2000, WakeSettings.maxStopMillis("Stop now"))
        assertEquals(4000, WakeSettings.maxStopMillis("please stop talking right now okay"))
    }

    @Test fun phraseValidationAndScripts() {
        assertTrue(WakeSettings.validPhrase("멈춰"))
        assertFalse(WakeSettings.validPhrase("a"))
        assertFalse(WakeSettings.validPhrase("x".repeat(41)))
        assertTrue(WakeSettings.koreanReadable("그만"))
        assertFalse(WakeSettings.koreanReadable("ストップ"))
    }

    @Test fun defaultsWithoutStorage() {
        assertEquals("Hey Hermes", WakeSettings.wakePhrase())
        assertEquals("Stop", WakeSettings.stopPhrase())
        assertTrue(WakeSettings.stopTexts().containsAll(listOf("stop", "스탑", "스톱")))
    }
}
