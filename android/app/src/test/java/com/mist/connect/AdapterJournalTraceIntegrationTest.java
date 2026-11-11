package com.mist.connect;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Real adapter -> sink -> parser -> persistent-record integration for cleanup evidence. */
public class AdapterJournalTraceIntegrationTest {
    private static File area(String name) throws Exception {
        File root = Files.createTempDirectory("adapter-journal-" + name).toFile();
        return root;
    }
    private static void until(java.util.function.BooleanSupplier ok) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!ok.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timeout");
            Thread.sleep(10);
        }
    }
    private static String logs(File dir) throws Exception {
        StringBuilder b = new StringBuilder();
        File[] files = new File(dir, "voice-journal").listFiles();
        assertNotNull(files);
        java.util.Arrays.sort(files);
        for (File f : files) if (f.getName().endsWith(".log")) b.append(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
        return b.toString();
    }
    @Test public void adapterSuppressedCleanupErrorsReachDurableTrace() throws Exception {
        File dir = area("trace");
        VoiceDiagnosticJournal journal = new VoiceDiagnosticJournal(dir, 999, 62);
        until(journal::testReady);
        SourceServicePlaybackAdapter owner = new SourceServicePlaybackAdapter(SourceServicePlaybackAdapter.Mode.OFFLINE_FIXTURE);
        owner.setTraceSink(event -> journal.offer(VoiceDiagnosticJournal.trace(event)));
        VoicePlaybackFailure primary = new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SINK_START, VoicePlaybackFailure.Category.IO, 500);
        primary.addSuppressed(new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SOURCE_CLOSE, VoicePlaybackFailure.Category.CLEANUP));
        primary.addSuppressed(new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SINK_ABORT, VoicePlaybackFailure.Category.CLEANUP));
        primary.addSuppressed(new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SINK_CLOSE, VoicePlaybackFailure.Category.CLEANUP));
        owner.serviceFailure(SourceServicePlaybackAdapter.ServiceStage.PROGRESS, primary);
        // VoiceRequestService's sink handler ends in VoiceDiagnostics.trace, which formats with
        // exactly this production parser; the process-wide singleton is attached by the service,
        // so this JVM test feeds the identical parser directly to a test-owned journal.
        for (String event : owner.diagnosticSnapshot()) journal.offer(VoiceDiagnosticJournal.trace(event));
        journal.flushAsync();
        until(() -> journal.written() > 0);
        journal.closeAsync();
        assertTrue(journal.awaitClosed(15000));
        String text = logs(dir);
        assertTrue(text.contains("secondary_0=SOURCE_CLOSE:CLEANUP"));
        assertTrue(text.contains("secondary_1=SINK_ABORT:CLEANUP"));
        assertTrue(text.contains("secondary_2=SINK_CLOSE:CLEANUP"));
        assertTrue(text.contains("failure_stage=SINK_START"));
        assertTrue(text.contains("category=IO"));
        assertTrue(text.contains("stage=service_progress_failed"));
        // Every record line keeps exactly one enqueue mono_ns plus at most one adapter event clock.
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            int mono = 0, at = -1;
            while ((at = line.indexOf(" mono_ns=", at + 1)) >= 0) mono++;
            assertEquals(line, 1, mono);
            int eventMono = 0, et = -1;
            while ((et = line.indexOf(" event_mono_ns=", et + 1)) >= 0) eventMono++;
            assertEquals(line, line.contains("event=TRACE") ? 1 : 0, eventMono);
        }
        assertFalse(text.contains("secret"));
    }
}
