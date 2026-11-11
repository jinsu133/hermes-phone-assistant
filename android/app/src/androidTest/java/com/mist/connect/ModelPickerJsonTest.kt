package com.mist.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared only. Pure fixture JSON; no pairing, service, microphone, provider or network access. */
@RunWith(AndroidJUnit4::class)
class ModelPickerJsonTest {
    private fun fixture() = JSONObject("""{
      "ok":true,"schema_version":2,"revision":7,"live_revision":null,
      "live_binding_matches":false,"operator_write_ready":false,
      "app_write_supported":false,"strict_physical_route":false,"mode":"runs",
      "model_effort_support_known":false,"selection_semantics":"request_preferences",
      "operator_command":"phone-execution","operator_action":"select",
      "host_provider":"p","host_model":"Exact/Model",
      "request_efforts":["none","minimal","low","medium","high","xhigh","max","ultra"],
      "selection":{"provider":"p","model":"Exact/Model","reasoning_effort":"high"},
      "providers":[{"id":"p","aliases":[],"nonselectable_aliases":[],
        "unavailable_models":null,"free_tier_pending":null,"auth_readiness":"ready","models":[
        {"id":"Exact/Model","reasoning_hint":true,"can_disable_reasoning":null,"supported_efforts":null,"unavailable":null}
      ]}]
    }""")
    private fun rejected(value: JSONObject) {
        try { ModelPickerClient.parse(value); fail("malformed response accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: org.json.JSONException) { }
    }
    @Test fun stockUnknownModelEffortsRemainUnknown() {
        val parsed = ModelPickerClient.parse(fixture())
        assertEquals(7L, parsed.revision)
        assertNull(parsed.liveRevision)
        assertFalse(parsed.liveMatches)
        assertEquals("high", parsed.selection.effort)
    }
    @Test fun coercionsAndFalseSuccessRejected() {
        rejected(fixture().put("revision", "7"))
        rejected(fixture().put("revision", 7.5))
        rejected(fixture().put("ok", "true"))
        rejected(fixture().put("app_write_supported", true))
        rejected(fixture().put("strict_physical_route", true))
        rejected(fixture().put("live_binding_matches", true))
        rejected(fixture().put("model_effort_support_known", true))
    }
    @Test fun missingFieldsCannotBecomeInheritance() {
        val value = fixture()
        value.getJSONObject("selection").remove("reasoning_effort")
        rejected(value)
    }
    @Test fun supportedEffortListIsNotFabricatedFromHints() {
        val value = fixture()
        value.getJSONArray("providers").getJSONObject(0).getJSONArray("models").getJSONObject(0)
            .put("supported_efforts", org.json.JSONArray("[\"high\"]"))
        rejected(value)
    }
    @Test fun coherentSavedAndLiveRevisionAllowed() {
        val parsed = ModelPickerClient.parse(fixture().put("live_revision", 7).put("live_binding_matches", true))
        assertTrue(parsed.liveMatches)
    }

    // Exact sorted stock custom_provider_aliases("My Proxy", "my-proxy") output.
    // Python offline regressions execute those installed pure functions on this synthetic input.
    private fun stockAliasFixture(): JSONObject {
        val value = fixture()
        val row = JSONObject(value.getJSONArray("providers").getJSONObject(0).toString())
            .put("id", "my-proxy")
            .put("aliases", org.json.JSONArray("""["custom:my-proxy","my proxy","my-proxy"]"""))
            .put("nonselectable_aliases", org.json.JSONArray("""["my proxy"]"""))
        value.getJSONArray("providers").put(row)
        return value
    }
    private fun commandRejected(snapshot: ModelPickerSnapshot, selection: ModelPickerSelection) {
        assertNotNull(ModelPickerContract.validationError(snapshot, selection))
        try { ModelPickerContract.operatorCommand(snapshot, selection); fail("command created") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun stockDisplayAliasDoesNotPoisonCanonicalOrUnrelatedProvider() {
        val parsed = ModelPickerClient.parse(stockAliasFixture())
        assertEquals(listOf("custom:my-proxy", "my proxy", "my-proxy"), parsed.providers[1].aliases)
        assertEquals(listOf("my proxy"), parsed.providers[1].nonselectableAliases)
        for (provider in listOf("my-proxy", "custom:my-proxy", "p")) {
            val selection = parsed.selection.copy(provider = provider)
            assertNull(ModelPickerContract.validationError(parsed, selection))
            assertTrue(ModelPickerContract.operatorCommand(parsed, selection).contains("\"provider\":\"$provider\""))
        }
        commandRejected(parsed, parsed.selection.copy(provider = "my proxy"))
    }
    @Test fun unsafeAliasOrFalseSelectabilityRejected() {
        for (alias in listOf("bad\nname", "bad\tname", "bad\u202ename", "x".repeat(513),
            "Bearer fixture-only", "my sk-fixture-only", "custom:ghp_fixture_only", "https://user:pass@example.invalid")) {
            val value = stockAliasFixture()
            value.getJSONArray("providers").getJSONObject(1)
                .put("aliases", org.json.JSONArray().put(alias))
            rejected(value)
        }
        val value = stockAliasFixture()
        value.getJSONArray("providers").getJSONObject(1).put("nonselectable_aliases", org.json.JSONArray())
        rejected(value)
    }
    @Test fun stockUnavailableAndPendingCannotCreateCommand() {
        // _apply_pricing with synthetic paid/free rows produces list[str]; pending is bool.
        for (pending in listOf(false, true)) {
            val value = stockAliasFixture()
            val row = value.getJSONArray("providers").getJSONObject(0)
            row.put("unavailable_models", org.json.JSONArray("""["Exact/Model"]"""))
                .put("free_tier_pending", if (pending) true else JSONObject.NULL)
            row.getJSONArray("models").getJSONObject(0).put("unavailable", true)
            val parsed = ModelPickerClient.parse(value)
            assertEquals(listOf("Exact/Model"), parsed.providers[0].unavailableModels)
            assertEquals(if (pending) true else null, parsed.providers[0].freeTierPending)
            assertEquals(true, parsed.providers[0].models[0].unavailable)
            commandRejected(parsed, parsed.selection)
            assertNull(ModelPickerContract.validationError(parsed, parsed.selection.copy(provider = "my-proxy")))
            val label = ModelPickerContract.availabilityLabel(parsed.providers[0], parsed.providers[0].models[0])
            assertTrue(label.contains(if (pending) "확인 대기" else "사용 불가"))
        }
    }
    @Test fun unknownAndClearListAreNotAvailabilityGuarantees() {
        val unknown = ModelPickerClient.parse(fixture()).providers[0]
        assertNull(unknown.unavailableModels)
        assertNull(unknown.models[0].unavailable)
        assertTrue(ModelPickerContract.availabilityLabel(unknown, unknown.models[0]).contains("미확인"))
        val value = fixture()
        val row = value.getJSONArray("providers").getJSONObject(0)
        row.put("unavailable_models", org.json.JSONArray()).put("free_tier_pending", false)
        row.getJSONArray("models").getJSONObject(0).put("unavailable", false)
        val parsed = ModelPickerClient.parse(value)
        assertNull(ModelPickerContract.validationError(parsed, parsed.selection))
        assertTrue(ModelPickerContract.availabilityLabel(parsed.providers[0], parsed.providers[0].models[0]).contains("미확인"))
        row.put("free_tier_pending", true)
        val pending = ModelPickerClient.parse(value)
        commandRejected(pending, pending.selection)
    }
    @Test fun oldOrIncompleteAvailabilitySchemaFailsClosed() {
        rejected(fixture().put("schema_version", 1))
        for (field in listOf("unavailable_models", "free_tier_pending", "nonselectable_aliases")) {
            val value = fixture()
            value.getJSONArray("providers").getJSONObject(0).remove(field)
            rejected(value)
        }
        for (bad in listOf("true", 1)) {
            val value = fixture()
            value.getJSONArray("providers").getJSONObject(0).put("free_tier_pending", bad)
            rejected(value)
        }
        val value = fixture()
        value.getJSONArray("providers").getJSONObject(0).getJSONArray("models").getJSONObject(0)
            .put("unavailable", false) // Cannot turn null source membership into false.
        rejected(value)
    }
}
