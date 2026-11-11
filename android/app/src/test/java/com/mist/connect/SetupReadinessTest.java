package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class SetupReadinessTest {
    @Test public void missingOrCoercedCapabilitiesNeverEnableVoice() {
        assertFalse(SetupReadiness.permitsVoice(null, true, true));
        assertFalse(SetupReadiness.permitsVoice("1", true, true));
        assertFalse(SetupReadiness.permitsVoice(1L, true, true));
        assertFalse(SetupReadiness.permitsVoice(1, "true", true));
        assertFalse(SetupReadiness.permitsVoice(1, true, "true"));
        assertFalse(SetupReadiness.permitsVoice(1, true, false));
        assertFalse(SetupReadiness.permitsVoice(2, true, true));
        assertTrue(SetupReadiness.permitsVoice(1, true, true));
    }
    @Test public void importedCredentialsAreNotConnectionVerification() {
        assertEquals(SetupReadiness.Phase.UNPAIRED, SetupReadiness.classify(false, true, true, true));
        assertEquals(SetupReadiness.Phase.UNREACHABLE, SetupReadiness.classify(true, false, true, true));
        assertEquals(SetupReadiness.Phase.HOST_UNSUPPORTED, SetupReadiness.classify(true, true, false, false));
        assertEquals(SetupReadiness.Phase.HOST_UNSUPPORTED, SetupReadiness.classify(true, true, true, false));
        assertEquals(SetupReadiness.Phase.AVAILABLE, SetupReadiness.classify(true, true, true, true));
    }
}
