package com.mist.connect;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit source wiring checks; not Android execution evidence. */
public class SetupVoiceExecutionWiringTest {
    private String source(String file) throws Exception {
        return new String(Files.readAllBytes(Paths.get(System.getProperty("phone.readiness.sources",
            "src/main/java/com/mist/connect")).resolve(file)), StandardCharsets.UTF_8);
    }
    @Test public void clientRequiresExplicitExecutionInsteadOfStrictUpgrade() throws Exception {
        String client = source("VoiceBridgeClient.kt");
        assertTrue(client.contains("fun requireVoiceExecution(token: String)"));
        assertTrue(client.contains("if (!permitsVoiceExecution(capabilities(token)))"));
        assertTrue(client.contains("value.optJSONObject(\"voice_execution\") ?: return false"));
        assertFalse(client.contains("requireStrictVoice(token)"));
        String ready = client.substring(client.indexOf("    fun ready("), client.indexOf("    fun cancellationSession("));
        assertTrue(ready.indexOf("requireVoiceExecution(token)") < ready.indexOf("open(\"/status\""));
    }
    @Test public void serviceRequiresCapabilityBeforeControllerAndWork() throws Exception {
        String service = source("VoiceRequestService.kt");
        String boundary = service.substring(service.indexOf("    private fun withController("));
        int admission = boundary.indexOf("VoiceBridgeClient.requireVoiceExecution(token)");
        assertTrue(admission >= 0);
        assertTrue(admission < boundary.indexOf("VoiceBridgeClient.streamingStatus(token)"));
        assertTrue(admission < boundary.indexOf("work()"));
        assertFalse(service.contains("requireStrictVoice(token)"));
    }
    @Test public void setupSharesAdmissionAndExplainsLimits() throws Exception {
        String setup = source("SetupActivity.kt");
        assertTrue(setup.contains("SetupReadiness.classify(true, true, VoiceBridgeClient.permitsVoiceExecution(value))"));
        assertFalse(setup.contains("value.opt(\"model_catalog\")"));
        assertTrue(setup.contains("R.string.setup_contract_note"));
        assertTrue(setup.contains("R.string.setup_available"));
        String sourceRoot = System.getProperty("phone.readiness.sources", "src/main/java/com/mist/connect");
        String en = new String(Files.readAllBytes(Paths.get(sourceRoot)
            .resolve("../../../../res/values/strings.xml").normalize()), StandardCharsets.UTF_8);
        String ko = new String(Files.readAllBytes(Paths.get(sourceRoot)
            .resolve("../../../../res/values-ko/strings.xml").normalize()), StandardCharsets.UTF_8);
        assertTrue(en.contains("/v1/runs"));
        assertTrue(en.contains("strict_voice is unsupported"));
        assertTrue(en.contains("apply only to plugin settings"));
        assertTrue(en.contains("does not prove actual voice operation or full product verification"));
        assertTrue(ko.contains("/v1/runs"));
        assertTrue(ko.contains("strict_voice는 지원하지 않으며"));
        assertTrue(ko.contains("플러그인 설정에만 적용"));
        assertTrue(ko.contains("제품 전체 검증이 완료되었다는 뜻은 아닙니다"));
    }
}
