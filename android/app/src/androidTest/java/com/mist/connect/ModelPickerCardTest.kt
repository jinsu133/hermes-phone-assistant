package com.mist.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Prepared only; fixture composable, not SetupActivity or a live paired service. */
class ModelPickerCardTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture() = ModelPickerSnapshot(7, 6, false, false, "p", "Exact/Model",
        listOf(ModelPickerProvider("p", emptyList(), "ready", listOf(ModelPickerModel("Exact/Model", true, null)))),
        listOf("none", "high", "xhigh"), ModelPickerSelection("p", "Exact/Model", "high"))

    @Test fun generatingOperatorHandoffDoesNotSaveOrCallRefresh() {
        var refreshCalls = 0
        compose.setContent {
            var draft by remember { mutableStateOf(fixture().selection) }
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelPickerCard(fixture(), draft, false, "fixture only",
                        onRefresh = { refreshCalls++ }, onSelection = { draft = it })
                }
            }
        }
        compose.onNodeWithText("운영자 전달 명령 만들기 (저장 아님)").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("운영자 전달 명령 만들기 (저장 아님)").performScrollTo().performClick()
        compose.onNodeWithText(ModelPickerContract.operatorCommand(fixture(), fixture().selection)).assertExists()
        compose.onNodeWithText("명령만 복사").assertExists()
        compose.runOnIdle { assertEquals(0, refreshCalls) }
    }

    @Test fun failedRefreshExposesNoStaleSelectionAction() {
        compose.setContent {
            MaterialTheme { ModelPickerCard(null, null, false, "카탈로그 확인 실패", {}, {}) }
        }
        compose.onNodeWithText("카탈로그 확인 실패").assertExists()
        compose.onNodeWithText("운영자 전달 명령 만들기 (저장 아님)").assertDoesNotExist()
    }

    @Test fun pendingEntitlementExplainedAndHandoffStaysDisabled() {
        val row = fixture().providers[0].copy(freeTierPending=true, unavailableModels=listOf("Exact/Model"),
            models=listOf(ModelPickerModel("Exact/Model", true, null, unavailable=true)))
        val snapshot = fixture().copy(providers=listOf(row))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelPickerCard(snapshot, snapshot.selection, false, "fixture only", {}, {})
                }
            }
        }
        compose.onNodeWithText("모델 이용 상태: 이용 권한 확인 대기 · 조회 시점 미확인 (선택 불가)")
            .performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("운영자 전달 명령 만들기 (저장 아님)").performScrollTo().assertIsNotEnabled()
    }

    @Test fun displayOnlyAliasIsVisibleWithoutChangingCanonicalSelection() {
        val row = fixture().providers[0].copy(id="my-proxy",
            aliases=listOf("custom:my-proxy", "my proxy", "my-proxy"), nonselectableAliases=listOf("my proxy"))
        val snapshot = fixture().copy(providers=listOf(row), selection=fixture().selection.copy(provider="my-proxy"))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelPickerCard(snapshot, snapshot.selection, false, "fixture only", {}, {})
                }
            }
        }
        compose.onNodeWithText("표시 전용 별칭 (공백 등을 보정하지 않으며 선택할 수 없습니다): my proxy")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("제공자: my-proxy").assertExists()
    }
}
