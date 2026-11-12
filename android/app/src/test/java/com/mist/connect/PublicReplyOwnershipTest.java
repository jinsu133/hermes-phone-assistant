package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Public display only: no actual network, provider, microphone, or phone actions. */
public final class PublicReplyOwnershipTest {
    private static Method publish() throws Exception {
        try {
            return ServiceUiPublication.class.getDeclaredMethod("publishFinal", ServiceUiPublication.Scope.class,
                String.class, String.class);
        } catch (NoSuchMethodException missing) {
            fail("No request-scoped public final-answer publication exists yet");
            throw missing;
        }
    }
    private static String preview(ServiceUiPublication publisher) throws Exception {
        Field f = ServiceUiPublication.Snapshot.class.getDeclaredField("publicFinalPreview");
        return (String) f.get(publisher.read());
    }
    @Test public void matchingRequestCanPublishButMismatchedRequestCannotReplaceIt() throws Exception {
        ServiceUiPublication p = new ServiceUiPublication(new Object(), () -> 100L);
        ServiceUiPublication.Scope session = p.start(1, ServiceUiPublication.Mode.HANDSFREE);
        ServiceUiPublication.Scope request = p.beginRequest(session, "operation-a");
        p.acceptRequest(request, "request-a");
        Method publish = publish();
        publish.invoke(p, request, "request-a", "Hello from the assistant");
        assertEquals("Hello from the assistant", preview(p));
        publish.invoke(p, request, "request-b", "Other request");
        assertEquals("Hello from the assistant", preview(p));
    }
    @Test public void offClearsPreviewAndLateReplyCannotRestoreIt() throws Exception {
        ServiceUiPublication p = new ServiceUiPublication(new Object(), () -> 100L);
        ServiceUiPublication.Scope session = p.start(1, ServiceUiPublication.Mode.HANDSFREE);
        ServiceUiPublication.Scope request = p.beginRequest(session, "operation-a");
        p.acceptRequest(request, "request-a");
        Method publish = publish();
        publish.invoke(p, request, "request-a", "Public reply");
        p.off(session, true);
        assertNull(preview(p));
        publish.invoke(p, request, "request-a", "Late reply");
        assertNull(preview(p));
    }
    @Test public void completedReplyRemainsDuringFollowupButClearsOnWakeWait() throws Exception {
        ServiceUiPublication p = new ServiceUiPublication(new Object(), () -> 100L);
        ServiceUiPublication.Scope session = p.start(1, ServiceUiPublication.Mode.HANDSFREE);
        ServiceUiPublication.Scope request = p.beginRequest(session, "operation-a");
        p.acceptRequest(request, "request-a");
        publish().invoke(p, request, "request-a", "완료된 답변");
        p.retireRequest(request, true);
        p.captureEvent(session, ServiceUiPublication.CaptureEvent.FOLLOW_UP);
        assertEquals("완료된 답변", preview(p));
        p.captureEvent(session, ServiceUiPublication.CaptureEvent.WAKE_WAIT);
        assertNull(preview(p));
    }
    @Test public void longPublicAnswerIsNotCutAtOldPreviewLimit() throws Exception {
        ServiceUiPublication p = new ServiceUiPublication(new Object(), () -> 100L);
        ServiceUiPublication.Scope session = p.start(1, ServiceUiPublication.Mode.HANDSFREE);
        ServiceUiPublication.Scope request = p.beginRequest(session, "operation-long");
        p.acceptRequest(request, "request-long");
        String answer = "가".repeat(400) + " 문장 끝";
        publish().invoke(p, request, "request-long", answer);
        assertEquals(answer, preview(p));
    }
    @Test public void publicPreviewExcludesReasoningAndCommonSecretPatterns() throws Exception {
        ServiceUiPublication p = new ServiceUiPublication(new Object(), () -> 100L);
        ServiceUiPublication.Scope session = p.start(1, ServiceUiPublication.Mode.HANDSFREE);
        ServiceUiPublication.Scope request = p.beginRequest(session, "operation-a");
        p.acceptRequest(request, "request-a");
        publish().invoke(p, request, "request-a", "Visible <think>hidden reasoning</think> token=abcdefghijklmnop Done");
        String result = preview(p);
        assertFalse(result.contains("hidden reasoning"));
        assertFalse(result.contains("abcdefghijklmnop"));
        assertTrue(result.contains("Visible"));
    }
}
