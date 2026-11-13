package com.mist.connect

import org.junit.Assert.*
import org.junit.Test

/** Prepared only. Run with :app:testDebugUnitTest after merging into the parent stage. */
class ModelPickerContractTest {
    private val selected = ModelPickerSelection("Provider", "Exact/Model", "high")
    private fun snapshot() = ModelPickerSnapshot(7, 6, false, false, "Provider", "Exact/Model",
        listOf(ModelPickerProvider("Provider", emptyList(), "ready", listOf(
            ModelPickerModel("Exact/Model", true, null), ModelPickerModel("plain", false, null),
            ModelPickerModel("mandatory", true, false))),
            ModelPickerProvider("missing", emptyList(), "not_ready", listOf(ModelPickerModel("m", true, null)))),
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"), selected)

    private val followsDefault = ModelPickerSelection(null, null, "high")

    @Test fun exactCommandContainsOnlySelectionAndRevision() {
        assertEquals("/phone-execution select 7 '{\"provider\":null,\"model\":null,\"reasoning_effort\":\"high\"}'",
            ModelPickerContract.operatorCommand(snapshot(), followsDefault))
    }
    @Test fun explicitModelIsRefusedBecauseTheDefaultProfileDecides() {
        assertNotNull(ModelPickerContract.validationError(snapshot(), selected))
        try { ModelPickerContract.operatorCommand(snapshot(), selected); fail("explicit model accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun inheritedNullAndNoneStayDifferent() {
        val inherited = ModelPickerSelection(null, null, null)
        assertNull(ModelPickerContract.validationError(snapshot(), inherited))
        assertTrue(ModelPickerContract.operatorCommand(snapshot(), inherited).contains("\"reasoning_effort\":null"))
        assertTrue(ModelPickerContract.operatorCommand(snapshot(), inherited.copy(effort="none"))
            .contains("\"reasoning_effort\":\"none\""))
    }
    @Test fun allAcceptedEffortsPreservedWithoutClamp() {
        for (effort in snapshot().efforts)
            assertTrue(ModelPickerContract.operatorCommand(snapshot(), followsDefault.copy(effort=effort))
                .contains("\"reasoning_effort\":\"$effort\""))
    }
    @Test fun invalidIdentifiersNeverNormalized() {
        for (model in listOf("exact/model", " Exact/Model", "Exact/Model\n", "x';stop", "$(shell)"))
            assertNotNull(ModelPickerContract.validationError(snapshot(), selected.copy(model=model)))
    }
    @Test fun authKnownUnsupportedAndPartialRoutesFailClosed() {
        for (value in listOf(selected.copy(provider="missing", model="m"), selected.copy(model="plain"),
            selected.copy(model="mandatory", effort="none"), selected.copy(model=null),
            selected.copy(provider=null), selected.copy(effort="HIGH")))
            assertNotNull(ModelPickerContract.validationError(snapshot(), value))
    }
    @Test fun draftAndLiveBindingAreIndependent() {
        val s = snapshot()
        assertTrue(ModelPickerContract.savedMatches(s, selected))
        assertFalse(s.liveMatches)
        assertFalse(ModelPickerContract.savedMatches(s, selected.copy(effort="xhigh")))
    }
    @Test fun switchingProviderDoesNotRewriteModelOrEffort() {
        val draft = selected.copy(provider="missing")
        assertEquals("Exact/Model", draft.model)
        assertEquals("high", draft.effort)
        assertNotNull(ModelPickerContract.validationError(snapshot(), draft))
    }
    @Test fun revisionIsExactLongNotFloat() {
        val s = snapshot().copy(revision=Long.MAX_VALUE)
        assertTrue(ModelPickerContract.operatorCommand(s, followsDefault).contains(Long.MAX_VALUE.toString()))
    }
    @Test fun stockAliasMetadataAndWritableIdentifiersAreSeparate() {
        for (alias in listOf("my proxy", "My Proxy", "my  proxy", "my proxy ")) {
            assertTrue(ModelPickerContract.validMetadataAlias(alias))
            assertFalse(ModelPickerContract.validIdentifier(alias))
            assertNotNull(ModelPickerContract.validationError(snapshot(), selected.copy(provider=alias)))
        }
        for (alias in listOf("Bearer fixture-only", "my sk-fixture-only", "my\nproxy", "x".repeat(513)))
            assertFalse(ModelPickerContract.validMetadataAlias(alias))
    }
    @Test fun unavailableAndPendingBlockCommandEvenWithReadyAuthentication() {
        val row = snapshot().providers[0]
        for (locked in listOf(
            row.copy(models=row.models.map { it.copy(unavailable=true) }, unavailableModels=listOf("Exact/Model")),
            row.copy(freeTierPending=true))) {
            val state = snapshot().copy(providers=listOf(locked))
            assertNotNull(ModelPickerContract.validationError(state, selected))
            try { ModelPickerContract.operatorCommand(state, selected); fail("locked selection accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
