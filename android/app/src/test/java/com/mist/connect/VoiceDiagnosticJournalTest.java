package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Hardware-free tests; retained fixture output lives under voice.diagnostic.testRoot. */
public class VoiceDiagnosticJournalTest {
    private static File area(String name)throws Exception {
        String base=System.getProperty("voice.diagnostic.testRoot",System.getProperty("java.io.tmpdir"));
        return Files.createTempDirectory(new File(base).toPath(),name+"-").toFile();
    }
    private static void until(BooleanSupplier predicate)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!predicate.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);
        assertTrue("bounded wait expired",predicate.getAsBoolean());
    }
    private static void finish(VoiceDiagnosticJournal j)throws Exception{j.closeAsync();assertTrue(j.awaitClosed(10000));}
    private static String logs(File parent)throws Exception {
        StringBuilder b=new StringBuilder();File[] files=new File(parent,"voice-journal").listFiles();
        if(files!=null){Arrays.sort(files);for(File f:files)if(f.getName().endsWith(".log"))b.append(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
        return b.toString();
    }
    private static VoiceDiagnosticJournal open(File parent,long quota,int cap,Runnable hook){return new VoiceDiagnosticJournal(parent,123,60,65536,quota,cap,256,hook);}
    @Test public void rolloverPreservesEarliestAndCleanRestart()throws Exception {
        File dir=area("rollover");VoiceDiagnosticJournal j=open(dir,2*1024*1024,64,()->{});until(j::testReady);
        for(int batch=0;batch<24;batch++){
            for(int n=0;n<24;n++)assertTrue(j.offer(VoiceDiagnosticJournal.trace("stage=request_accepted request_local="+(batch*24+n))));
            final long target=(batch+1)*24;until(()->j.written()>=target);
        }
        finish(j);assertEquals("closed",j.health());assertEquals(0,j.dropped());
        String content=logs(dir);assertTrue(content.length()>65536);assertTrue(content.contains("request_local=0 "));
        assertTrue(content.contains("SESSION_CLOSE"));assertTrue(content.contains("latency_scope=app_stage_monotonic_not_speech_end"));
        Map<String,byte[]> old=new HashMap<>();for(File f:new File(dir,"voice-journal").listFiles())old.put(f.getName(),Files.readAllBytes(f.toPath()));
        VoiceDiagnosticJournal again=open(dir,2*1024*1024,64,()->{});until(again::testReady);assertNotEquals(j.session,again.session);
        assertEquals(0,again.previousUnclosed());finish(again);
        for(Map.Entry<String,byte[]> e:old.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(new File(new File(dir,"voice-journal"),e.getKey()).toPath()));
    }
    @Test public void privacyGraphAndLatencySchema()throws Exception {
        File dir=area("privacy");VoiceDiagnosticJournal j=open(dir,1024*1024,64,()->{});until(j::testReady);
        String secret="SECRET_CANARY_transcript_screen_token_https://example.invalid/?key=abc";
        IOException primary=new IOException(secret,new IllegalArgumentException(secret));primary.addSuppressed(new SecurityException(secret));
        j.offer(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.FAILURE,"voice_request",primary));
        j.offer(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.EXCEPTION,secret,new RuntimeException(secret)));
        j.offer(VoiceDiagnosticJournal.playback(secret,secret));
        j.offer(VoiceDiagnosticJournal.trace("stage="+secret+" request_local=123 error="+secret+" correlation_id="+secret));
        j.offer(VoiceDiagnosticJournal.trace(secret));finish(j);
        String text=logs(dir);assertFalse(text.contains("SECRET_CANARY"));assertFalse(text.contains("https:"));assertFalse(text.contains("example.invalid"));
        assertTrue(text.contains("graph_0=-1:root:IOException"));assertTrue(text.contains("graph_1=0:cause:IllegalArgumentException"));
        assertTrue(text.contains("graph_2=0:suppressed:SecurityException"));assertTrue(text.contains("request_local=123"));
        assertTrue(text.contains("wall_ms="));assertTrue(text.contains("mono_ns="));assertTrue(text.contains("pid=123 version=60"));
        long previous=0;for(String line:text.split("\n")){String value=line.split(" seq=")[1].split(" ")[0];assertEquals(++previous,Long.parseLong(value));}
    }
    @Test public void overflowIsNonblockingAndDurableGap()throws Exception {
        File dir=area("overflow");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        VoiceDiagnosticJournal j=open(dir,1024*1024,2,()->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}});
        assertTrue(entered.await(10,TimeUnit.SECONDS));
        assertTrue(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));assertTrue(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));
        assertFalse(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));assertEquals("incomplete",j.health());assertFalse(j.testReady());
        // Returned while writer is explicitly blocked: producers cannot depend on disk/writer progress.
        assertEquals(1,release.getCount());release.countDown();finish(j);
        assertEquals(1,j.dropped());assertTrue(logs(dir).contains("event=GAP dropped=1 health=incomplete"));
    }
    @Test public void quotaPreservesEvidenceAndRefusesReadiness()throws Exception {
        File dir=area("quota");VoiceDiagnosticJournal j=open(dir,8192,64,()->{});until(j::testReady);
        for(int batch=0;batch<8;batch++){
            for(int i=0;i<16;i++)j.offer(VoiceDiagnosticJournal.trace("stage=request_accepted request_local="+i));
            Thread.sleep(25);
        }
        finish(j);assertEquals("incomplete",j.health());assertFalse(j.testReady());assertTrue(j.dropped()>0);
        String text=logs(dir);assertTrue(text.contains("SESSION_OPEN"));assertTrue(text.contains("event=GAP"));
        long bytes=0;for(File f:new File(dir,"voice-journal").listFiles())bytes+=f.length();assertTrue(bytes<=8192);
        Map<String,byte[]> old=new HashMap<>();for(File f:new File(dir,"voice-journal").listFiles())old.put(f.getName(),Files.readAllBytes(f.toPath()));
        VoiceDiagnosticJournal again=open(dir,8192,4,()->{});until(again::stopped);assertEquals("incomplete",again.health());
        for(Map.Entry<String,byte[]> e:old.entrySet())assertArrayEquals(e.getValue(),Files.readAllBytes(new File(new File(dir,"voice-journal"),e.getKey()).toPath()));
    }
    @Test public void unavailableDirectoryIsIncomplete()throws Exception {
        File dir=area("io");File obstacle=new File(dir,"voice-journal");Files.write(obstacle.toPath(),new byte[]{4,2});
        VoiceDiagnosticJournal j=open(dir,8192,4,()->{});until(j::stopped);assertFalse(j.testReady());assertEquals("incomplete",j.health());
        assertFalse(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));assertTrue(j.dropped()>0);assertArrayEquals(new byte[]{4,2},Files.readAllBytes(obstacle.toPath()));
    }
    @Test public void realProcessDeathDetectedWithoutDeletingPriorSession()throws Exception {
        File dir=area("restart");String java=new File(System.getProperty("java.home"),"bin/java").getAbsolutePath();
        Process child=new ProcessBuilder(java,"-Xmx48m","-cp",System.getProperty("java.class.path"),getClass().getName(),dir.getAbsolutePath()).redirectErrorStream(true).redirectOutput(new File(dir,"child.txt")).start();
        assertTrue(child.waitFor(15,TimeUnit.SECONDS));assertEquals(0,child.exitValue());String old=logs(dir);assertFalse(old.contains("SESSION_CLOSE"));
        VoiceDiagnosticJournal j=open(dir,1024*1024,8,()->{});until(()->j.health().equals("incomplete"));assertEquals(1,j.previousUnclosed());assertEquals("incomplete",j.health());assertFalse(j.testReady());finish(j);
        String now=logs(dir);assertTrue(now.contains(old.trim()));assertTrue(now.contains("event=RECOVERY previous_unclosed=1"));
    }
    public static void main(String[] args)throws Exception {
        VoiceDiagnosticJournal j=open(new File(args[0]),1024*1024,8,()->{});until(j::testReady);
        j.offer(VoiceDiagnosticJournal.trace("request_accepted"));until(()->j.written()==1);Runtime.getRuntime().halt(0);
    }
    @Test public void firstDuplexFenceSurvivesCleanupAndExactBoundary()throws Exception {
        DuplexCaptureWorker worker=new DuplexCaptureWorker(null);long capture=1000000000L;
        assertFalse(worker.accept(new short[160],capture,null,capture+250000001L));
        DuplexCaptureWorker.FailureSnapshot first=worker.failureSnapshot();assertEquals(DuplexCaptureWorker.FailureReason.CAPTURE_AGE,first.reason);
        assertEquals(250000001L,first.captureAgeNanos);assertEquals(capture,first.captureNanos);assertEquals(capture+250000001L,first.nowNanos);
        assertEquals(-1,first.renderNanos);assertEquals(0,first.captureFrames);assertEquals(0,first.renderFrames);
        worker.revoke();worker.processingFailed();worker.accept(null,0,null,0);assertSame(first,worker.failureSnapshot());
        CaptureCleanup.Outcome cleanup=CaptureCleanup.release(null,worker::close,null);assertFalse(cleanup.confirmed());assertSame(first,worker.failureSnapshot());
        String record=VoiceDiagnosticJournal.display(VoiceDiagnosticJournal.duplex(true,first,false,new IOException("secret"),cleanup.aecFailure));
        assertTrue(record.contains("reason=CAPTURE_AGE"));assertTrue(record.contains("capture_age_ns=250000001"));assertTrue(record.contains("cleanup_confirmed=false"));assertFalse(record.contains("secret"));
        DuplexCaptureWorker boundary=new DuplexCaptureWorker(null);boundary.accept(new short[160],capture,null,capture+250000000L);
        assertEquals(DuplexCaptureWorker.FailureReason.REFERENCE_MISSING,boundary.failureSnapshot().reason);
        assertFalse(worker.acousticallyQualified());
    }
    @Test public void writerIoFailureCountsLostEventWithoutCleanClose()throws Exception {
        File dir=area("writer-io");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        VoiceDiagnosticJournal j=open(dir,1024*1024,8,()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}});
        assertTrue(entered.await(10,TimeUnit.SECONDS));
        java.lang.reflect.Field field=VoiceDiagnosticJournal.class.getDeclaredField("out");field.setAccessible(true);
        ((FileOutputStream)field.get(j)).close(); // fault only the test-owned journal's writer
        assertTrue(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));release.countDown();until(j::stopped);
        assertEquals("incomplete",j.health());assertFalse(j.testReady());assertEquals(1,j.dropped());
        assertTrue(logs(dir).contains("SESSION_OPEN"));assertFalse(logs(dir).contains("SESSION_CLOSE"));
        assertFalse(new File(new File(dir,"voice-journal"),j.session+".closed").exists());
    }
    @Test public void adapterEventTimeAndSecondaryCleanupAreDistinctAndKept()throws Exception {
        File dir=area("trace-keys");VoiceDiagnosticJournal j=open(dir,1024*1024,64,()->{});until(j::testReady);
        VoicePlaybackFailure primary=new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SINK_START,VoicePlaybackFailure.Category.IO,0);
        primary.addSuppressed(new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SOURCE_CLOSE,VoicePlaybackFailure.Category.CLEANUP));
        primary.addSuppressed(new VoicePlaybackFailure(VoicePlaybackFailure.Stage.SINK_ABORT,VoicePlaybackFailure.Category.CLEANUP));
        j.offer(VoiceDiagnosticJournal.trace("mono_ns=42 owner_local=1 stage=sink_start error=IOError secondary_0=SOURCE_CLOSE:CLEANUP secondary_1=SINK_ABORT:CLEANUP secondary_2=NOT_A_STAGE:NOT_A_CATEGORY"));
        finish(j);String text=logs(dir);
        for(String line:text.split("\n")){
            if(!line.contains("event=TRACE"))continue;
            int seen=0,at=-1;while((at=line.indexOf(" mono_ns=",at+1))>=0)seen++;
            assertEquals(1,seen);assertTrue(line.contains(" event_mono_ns=42"));assertTrue(line.contains(" secondary_0=SOURCE_CLOSE:CLEANUP"));
            assertTrue(line.contains(" secondary_1=SINK_ABORT:CLEANUP"));assertTrue(line.contains(" secondary_2=unknown:unknown"));
        }
        assertTrue(text.contains("secondary_0=SOURCE_CLOSE:CLEANUP"));
    }
    @Test public void knownLossSurvivesCleanLookingCloseAndRestart()throws Exception {
        File dir=area("lossy-reopen");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        VoiceDiagnosticJournal j=open(dir,1024*1024,2,()->{entered.countDown();
            try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}});
        assertTrue(entered.await(10,TimeUnit.SECONDS));
        assertTrue(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));assertTrue(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));
        assertFalse(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));assertEquals("incomplete",j.health());release.countDown();finish(j);
        assertTrue(new File(new File(dir,"voice-journal"),j.session+".closed").isFile());
        VoiceDiagnosticJournal again=open(dir,1024*1024,32,()->{});
        until(()->again.health().equals("incomplete"));finish(again);
        assertEquals(1,again.previousLossyCloses());assertEquals("incomplete",again.health());assertFalse(again.testReady());
        assertTrue(logs(dir).contains("previous_incomplete_close=1"));
    }
    @Test public void lossAfterCleanCloseStaysVisibleOnRestart()throws Exception {
        File dir=area("late-drop");VoiceDiagnosticJournal j=open(dir,1024*1024,64,()->{});until(j::testReady);
        j.offer(VoiceDiagnosticJournal.trace("request_accepted"));finish(j);assertEquals("closed",j.health());
        // A producer that ran past close must not leave a genuinely healthy marker behind.
        assertFalse(j.offer(VoiceDiagnosticJournal.trace("request_accepted")));
        assertTrue(new File(new File(dir,"voice-journal"),j.session+".late-drop").isFile());
        VoiceDiagnosticJournal again=open(dir,1024*1024,64,()->{});until(()->again.health().equals("incomplete"));
        assertEquals(1,again.previousLossyCloses());assertFalse(again.testReady());finish(again);
        assertTrue(logs(dir).contains("previous_incomplete_close=1"));
    }
    @Test public void exceptionGraphIsBoundedEvenWithCycles() {
        Throwable root=new IOException("secret");Throwable cursor=root;
        for(int i=0;i<20;i++){Throwable next=new RuntimeException("secret");cursor.initCause(next);cursor=next;}
        cursor.addSuppressed(root);
        String record=VoiceDiagnosticJournal.display(VoiceDiagnosticJournal.failure(VoiceDiagnosticJournal.Event.FAILURE,"voice_request",root));
        assertTrue(record.contains("graph_7="));assertFalse(record.contains("graph_8="));assertTrue(record.contains("graph_truncated=true"));
        assertFalse(record.contains("secret"));
    }
    @Test public void nativeMissingOpenIsDistinctFromCleanup() {
        final Throwable[] observed={null};DuplexCaptureWorker w=DuplexCaptureWorker.openIfPackaged(e->observed[0]=e);
        assertNull(w);assertTrue(observed[0] instanceof UnsatisfiedLinkError);
        assertTrue(VoiceDiagnosticJournal.display(VoiceDiagnosticJournal.nativeOpen(false,observed[0])).contains("opened=false type=UnsatisfiedLinkError"));
        assertTrue(CaptureCleanup.release(null,null,null).confirmed());
    }
}
