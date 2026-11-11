package com.mist.connect;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure-JVM B-mode contract checks, not device/provider acceptance. */
public class SetupVoiceExecutionTest {
    private Map<String, Object> ready() {
        Map<String, Object> value = new HashMap<>();
        value.put("mode", "runs");
        value.put("ready", true);
        value.put("contract", "hermes-runs-v1");
        value.put("scoped_cancel", true);
        value.put("strict_physical_route", false);
        value.put("settings_cas_scope", "plugin");
        value.put("error", null);
        return value;
    }
    private boolean permits(Map<String, Object> execution) {
        return SetupReadiness.permitsVoiceExecution(1, true, false, execution);
    }
    @Test public void explicitRunsIsReadyWithoutClaimingStrictSupport() {
        assertTrue(permits(ready()));
        assertFalse(SetupReadiness.permitsVoice(1, true, false));
        assertEquals(SetupReadiness.Phase.AVAILABLE, SetupReadiness.classify(true, true, permits(ready())));
    }
    @Test public void everyExecutionFieldIsRequired() {
        assertFalse(permits(null));
        assertFalse(permits(new HashMap<>()));
        for (String field : ready().keySet()) {
            Map<String, Object> value = ready(); value.remove(field);
            assertFalse("missing " + field, permits(value));
        }
    }
    @Test public void executionValuesAreExactAndNeverCoerced() {
        Object[][] bad = {
            {"mode", "inject_message"}, {"mode", "RUNS"}, {"mode", "runs "},
            {"ready", false}, {"ready", "true"}, {"ready", 1},
            {"contract", "hermes-runs-v2"}, {"contract", ""},
            {"scoped_cancel", false}, {"scoped_cancel", "true"},
            {"strict_physical_route", true}, {"strict_physical_route", "false"},
            {"settings_cas_scope", "global"}, {"settings_cas_scope", "Plugin"},
            {"error", "HOST_ROUTE_CONTRACT_UNAVAILABLE"}, {"error", ""}, {"error", false}
        };
        for (Object[] mutation : bad) {
            Map<String, Object> value = ready(); value.put((String) mutation[0], mutation[1]);
            assertFalse(mutation[0] + "=" + mutation[1], permits(value));
        }
        for (String field : ready().keySet()) {
            if (field.equals("error")) continue;
            Map<String, Object> value = ready(); value.put(field, null);
            assertFalse("null " + field, permits(value));
        }
    }
    @Test public void envelopeAndStrictFlagMustMatchApprovedBContract() {
        for (Object schema : new Object[]{null, "1", 1L, 1.0, 2, true})
            assertFalse(SetupReadiness.permitsVoiceExecution(schema, true, false, ready()));
        for (Object ok : new Object[]{null, false, "true", 1})
            assertFalse(SetupReadiness.permitsVoiceExecution(1, ok, false, ready()));
        for (Object strict : new Object[]{null, true, "false", 0})
            assertFalse(SetupReadiness.permitsVoiceExecution(1, true, strict, ready()));
    }
    @Test public void readinessCannotOverridePairingOrTransportFailure() {
        assertEquals(SetupReadiness.Phase.UNPAIRED, SetupReadiness.classify(false, true, true));
        assertEquals(SetupReadiness.Phase.UNREACHABLE, SetupReadiness.classify(true, false, true));
        assertEquals(SetupReadiness.Phase.HOST_UNSUPPORTED, SetupReadiness.classify(true, true, false));
    }
}
