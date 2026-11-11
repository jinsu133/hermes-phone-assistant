package com.mist.connect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Pure-JVM cancellation tests. No admission bypass, network, Android runtime or providers.
 * Service/client wiring assertions are source evidence, not Android execution evidence.
 */
public final class ScopedVoiceCancellationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        ScopedVoiceCancellation owner = new ScopedVoiceCancellation();
        ScopedVoiceCancellation.Scope upload = owner.begin("epoch-a", "upload-a");
        ScopedVoiceCancellation.Scope stop = owner.cancel();
        check(stop == upload && stop.requestId == null, "preaccept snapshot must retain ingress ID");
        check(owner.blocked(), "pending cancellation blocks admission");
        check(owner.accept(upload, "epoch-a", "upload-a", "request-a") == null, "late acceptance cannot restore owner");
        owner.acknowledge(stop, true);
        check(!owner.blocked(), "confirmed clean ingress can unblock");

        ScopedVoiceCancellation.Scope nextUpload = owner.begin("epoch-a", "upload-b");
        ScopedVoiceCancellation.Scope accepted = owner.accept(nextUpload, "epoch-a", "upload-b", "request-b");
        check(accepted.requestId.equals("request-b"), "acceptance binds request ID");
        check(owner.accept(upload, "epoch-a", "upload-a", "request-a") == null, "old acceptance cannot replace new request");
        owner.acknowledge(stop, false);
        check(!owner.blocked(), "old cleanup callback cannot poison newer request");
        owner.complete(upload);
        check(owner.cancel() == accepted, "old complete cannot erase newer request");
        owner.acknowledge(accepted, false);
        check(owner.blocked(), "cleanup uncertainty remains blocked");
        owner.acknowledge(accepted, true);
        check(owner.blocked(), "late success cannot erase earlier failure");
        boolean denied = false;
        try { owner.begin("epoch-a", "upload-c"); } catch (IllegalStateException expected) { denied = true; }
        check(denied, "new admission must fail after uncertain cleanup");

        ScopedVoiceCancellation identity = new ScopedVoiceCancellation();
        ScopedVoiceCancellation.Scope original = identity.begin("epoch-a", "operation-a");
        for (String[] fields : new String[][] {{"epoch-b", "operation-a", "request-a"},
                {"epoch-a", "operation-b", "request-a"}, {"epoch-a", "operation-a", "bad/request"}}) {
            denied = false;
            try { identity.accept(original, fields[0], fields[1], fields[2]); }
            catch (IllegalArgumentException expected) { denied = true; }
            check(denied, "changed acceptance scope must fail");
        }
        check(identity.cancel() == original, "failed acceptance must retain cancellable upload scope");
        check(identity.cancel() == null, "second cancel must not look up current/global remote request");

        ScopedVoiceCancellation raced = new ScopedVoiceCancellation();
        ScopedVoiceCancellation.Scope uploading = raced.begin("epoch-race", "op-race");
        CountDownLatch acceptedAtServer = new CountDownLatch(1), deliverResponse = new CountDownLatch(1);
        AtomicReference<ScopedVoiceCancellation.Scope> delivered = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread io = new Thread(() -> {
            try {
                acceptedAtServer.countDown();
                if (!deliverResponse.await(3, TimeUnit.SECONDS)) throw new AssertionError("acceptance barrier timeout");
                delivered.set(raced.accept(uploading, "epoch-race", "op-race", "request-race"));
            } catch (Throwable error) { failure.set(error); }
        });
        io.start();
        check(acceptedAtServer.await(3, TimeUnit.SECONDS), "upload reached deterministic acceptance boundary");
        ScopedVoiceCancellation.Scope captured = raced.cancel();
        deliverResponse.countDown(); io.join(3000);
        check(!io.isAlive() && failure.get() == null, "race worker joined without hidden failure");
        check(delivered.get() == null && captured.ingressOperationId.equals("op-race"), "lost/late response uses immutable ingress cancel");

        Path sources = Path.of(args[0]);
        String service = new String(Files.readAllBytes(sources.resolve("VoiceRequestService.kt")), java.nio.charset.StandardCharsets.UTF_8);
        String client = new String(Files.readAllBytes(sources.resolve("VoiceBridgeClient.kt")), java.nio.charset.StandardCharsets.UTF_8);
        check(service.contains("VoiceBridgeClient.submitAudio(token, wav, uploadScope, beforeUpload = {")
            && service.contains("playback.consumeUpload(permit,") && service.contains("cancellation.current(uploadScope)"),
            "production service submits scoped upload through current-owner effect gate");
        check(service.contains("cancellation.accept(uploadScope, accepted.optString(\"session_generation\")"), "service validates scoped acceptance");
        check(service.contains("cancellation.cancel().also { activeRequestId = \"\" }"), "snapshot precedes active ID clearing");
        check(service.contains("private val cancellation = ScopedVoiceCancellation()"), "service retains process cleanup owner");
        check(service.contains("playback.blocked() || cancellation.blocked()"), "service admission includes remote cleanup block");
        check(!service.contains("VoiceBridgeClient.cancelTurn(token)"), "no remaining global remote cancel call");
        check(!service.contains("stopService(Intent(this, McpService::class.java))"), "request STOP does not shut down MCP service");
        check(client.contains("X-Voice-Ingress-Operation") && client.contains("X-Voice-Session-Generation"), "client carries preaccept scope headers");
        String cancel = client.substring(client.indexOf("    private fun revokeTurn("), client.indexOf("    /** Worker only:"));
        check(!cancel.contains("catch"), "transport/ACK errors must propagate from cancellation");
        check(cancel.contains("return response.opt(\"cleanup_confirmed\") == true")
            && client.contains("if (!revokeTurn(token, scope)) throw"), "HTTP 200 alone is not cleanup evidence");
        check(client.contains("fun learningStatus(") && client.contains("fun requireVoiceExecution("), "learning and explicit execution admission APIs retained");
        check(service.contains("UnlockCoordinator.await(this)"), "protected Android authentication retained");
        System.out.println("PASS " + checks + " scoped cancellation checks (pure Java + explicit source wiring assertions)");
    }
}
