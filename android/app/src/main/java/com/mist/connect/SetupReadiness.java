package com.mist.connect;

import java.util.Map;

/** Capability validation and display state, not microphone consent or actuator authority. */
public final class SetupReadiness {
    public enum Phase { UNCHECKED, CHECKING, UNPAIRED, UNREACHABLE, HOST_UNSUPPORTED, AVAILABLE }
    private SetupReadiness() {}
    /** B-mode readiness does not depend on an unrelated model-picker capability. */
    public static Phase classify(boolean paired, boolean reachable, boolean voiceExecution) {
        if (!paired) return Phase.UNPAIRED;
        if (!reachable) return Phase.UNREACHABLE;
        return voiceExecution ? Phase.AVAILABLE : Phase.HOST_UNSUPPORTED;
    }
    /** Legacy strict capability classifier; never use it to admit runs mode. */
    public static Phase classify(boolean paired, boolean reachable, boolean strictVoice, boolean modelCatalog) {
        if (!paired) return Phase.UNPAIRED;
        if (!reachable) return Phase.UNREACHABLE;
        return strictVoice && modelCatalog ? Phase.AVAILABLE : Phase.HOST_UNSUPPORTED;
    }
    /** Approved B contract. No coercion, missing-field defaults, or strict-route upgrade. */
    public static boolean permitsVoiceExecution(Object schemaVersion, Object ok, Object strictVoice,
                                                Map<String, ?> execution) {
        return schemaVersion instanceof Integer && ((Integer) schemaVersion) == 1
            && Boolean.TRUE.equals(ok) && Boolean.FALSE.equals(strictVoice)
            && execution != null
            && "runs".equals(execution.get("mode"))
            && Boolean.TRUE.equals(execution.get("ready"))
            && "hermes-runs-v1".equals(execution.get("contract"))
            && Boolean.TRUE.equals(execution.get("scoped_cancel"))
            && Boolean.FALSE.equals(execution.get("strict_physical_route"))
            && "plugin".equals(execution.get("settings_cas_scope"))
            && execution.containsKey("error") && execution.get("error") == null;
    }
    /** Legacy strict contract retained separately; strict_voice=false still fails. */
    public static boolean permitsVoice(Object schemaVersion, Object ok, Object strictVoice) {
        return schemaVersion instanceof Integer && ((Integer) schemaVersion) == 1
            && Boolean.TRUE.equals(ok) && Boolean.TRUE.equals(strictVoice);
    }
}
