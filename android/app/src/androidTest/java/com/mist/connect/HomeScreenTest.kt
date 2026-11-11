package com.mist.connect

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Prepared only. Renderer harness, NOT live MainActivity/service or acoustic acceptance. */
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun recomposeAndSavedUiRestorationDoNotDispatch() {
        val commands = mutableListOf<HomeCommand>()
        val state = mutableStateOf(CompanionUiState("fixture"))
        val actions = listOf(
            HomeAction(HomeCommand.LISTEN_ON, "듣기 ON", HomeSection.PRIMARY),
            HomeAction(HomeCommand.LISTEN_OFF, "듣기 OFF", HomeSection.PRIMARY),
            HomeAction(HomeCommand.SETUP, "설치·모델 지원 상태 확인", HomeSection.EVERYDAY))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { HomeScreen(state.value, "", actions) { commands.add(it) } }
        rule.runOnIdle {
            state.value = state.value.copy(sequence = 1, readiness = SetupReadiness.Phase.UNPAIRED)
        }
        rule.runOnIdle {
            state.value = state.value.copy(sequence = 2, blocked = UiBlocked.BLOCKED, phase = UiPhase.BLOCKED)
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(emptyList<HomeCommand>(), commands) }
        rule.onNodeWithTag("home-LISTEN_OFF").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(HomeCommand.LISTEN_OFF), commands) }
        rule.runOnIdle { state.value = CompanionUiReducer.offRequested(state.value) }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(listOf(HomeCommand.LISTEN_OFF), commands) }
    }

    @Test fun oneStartGestureOneCommandNoApprovalCommand() {
        var starts = 0
        var others = 0
        val state = mutableStateOf(CompanionUiState("fixture"))
        val action = HomeAction(HomeCommand.LISTEN_ON, "듣기 ON", HomeSection.PRIMARY)
        rule.setContent { HomeScreen(state.value, "", listOf(action)) {
            if (it == HomeCommand.LISTEN_ON) starts++ else others++
        } }
        rule.onNodeWithTag("home-LISTEN_ON").performScrollTo().performClick()
        rule.runOnIdle { state.value = state.value.copy(sequence = 2) }
        rule.runOnIdle { assertEquals(1, starts); assertEquals(0, others) }
    }
}
