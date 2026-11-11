package com.mist.connect
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
object VoiceDiagnostics { val wakes=AtomicInteger(); val stops=AtomicInteger(); val captures=AtomicInteger(); val silences=AtomicInteger(); fun nativeOpen(opened:Boolean,error:Throwable?) {}
 fun duplexExit(worker:DuplexCaptureWorker?,outcome:CaptureCleanup.Outcome,primary:Throwable?) {} }
data class Frame(val pcm:ShortArray,val text:String="",val final:Boolean=false,val end:Double=0.0,val wake:Boolean=false,val advanceMs:Long=100) {
 fun json(done:Boolean):String {
   val key=if(done) "text" else "partial"
   val words=if(done) "result" else "partial_result"
   return "{\"$key\":\"$text\",\"$words\":[{\"word\":\"$text\",\"end\":$end}]}"
 }
}
object Fixture {
 var clockMs=0L
 var frames=listOf<Frame>(); var current=Frame(ShortArray(1600)); var index=0
 var starts=0; var stops=0; var releases=0; var allocations=0; var decodeCalls=0
 var failStop=false; var failRelease=false
 fun reset(input:List<Frame>) { frames=input; index=0; clockMs=0L; starts=0; stops=0; releases=0; allocations=0; decodeCalls=0; failStop=false; failRelease=false }
}
fun tone()=ShortArray(1600) { if(it%32<16) 1000 else -1000 }
fun silence()=ShortArray(1600)
fun noise()=ShortArray(1600) { ((it*7919%6001)-3000).toShort() }
fun speech(n:Int, offset:Int=0)=List(n) { Frame(tone(),"요청 ${it+offset}",false,(it+offset+1)/10.0) }
fun quiet(n:Int)=List(n) { Frame(silence()) }
fun main() {
 val dir=File(System.getProperty("java.io.tmpdir"),"endpoint-fixture-${System.nanoTime()}")
 File(dir,"wake-model-v1/complete").apply { parentFile.mkdirs(); writeText("fixture only") }
 val context=android.content.Context(dir)
 var failed=0; var passed=0
 fun test(name:String, body:()->Unit) { try { body(); passed++; println("PASS $name") } catch(t:Throwable) { failed++; println("FAIL $name: ${t.javaClass.simpleName}: ${t.message}") } }
 fun capture(input:List<Frame>, followup:Boolean=true):ByteArray? {
   Fixture.reset(input)
   var owned=false; var cleaned=false
   val result=HandsfreeCapture(context,{true},{},{owned=it!=null},{outcome,_-> cleaned=outcome.confirmed()}).use { it.nextRequest(followup) }
   check(!owned && cleaned && Fixture.starts==1 && Fixture.stops==1 && Fixture.releases==1)
   return result
 }
 try {
 test("continuous speech beyond old ten-second cap") {
   val bytes=capture(speech(160)+quiet(35))!!
   check(Fixture.index>160) { "active speech truncated at frame ${Fixture.index}" }
   check(bytes.size>=160*3200)
 }
 test("whole long capture preserves every consumed sample through 27 seconds") {
   for (frames in listOf(60,160,270)) {
     val input=speech(frames)+quiet(35)
     val bytes=capture(input)!!
     check(Fixture.index>frames && bytes.size==Fixture.index*3200)
     for (i in 0 until Fixture.index) for (j in input[i].pcm.indices) {
       val offset=i*3200+j*2
       val actual=((bytes[offset].toInt() and 255) or (bytes[offset+1].toInt() shl 8)).toShort()
       check(actual==input[i].pcm[j]) { "whole sample mismatch at $i/$j" }
     }
     bytes.fill(0)
   }
 }
 test("short pause resumes and cancels pending end") {
   capture(speech(8)+quiet(9)+speech(15,8)+quiet(35))
   check(Fixture.index>32) { "ended during thinking pause at ${Fixture.index}" }
 }
 test("silence returns empty without upload") { check(capture(quiet(110))!!.isEmpty()) }
 test("synthetic broadband noise is not a command") { check(capture(List(110){Frame(noise())})!!.isEmpty()) }
 test("stale partial cannot prolong trailing noise or execute ambiguous audio") {
   var rejected=false
   try { capture(speech(10)+List(40){ Frame(noise(),"요청 9",false,1.0) }) }
   catch(e:RuntimeException) { rejected=e.message=="endpoint_ambiguous" }
   check(rejected) { "ambiguous noisy tail was not rejected" }
   check(Fixture.index<=40) { "noise extended capture beyond three seconds" }
   check(Fixture.stops==1 && Fixture.releases==1)
 }
 test("actual speech end plus silence finishes within three seconds") {
   val bytes=capture(speech(20)+quiet(35))!!
   check(bytes.isNotEmpty() && Fixture.index in 21..50)
   println("METRIC synthetic quiet-tail decision_ms=${(Fixture.index-20)*100}")
 }
 test("long capture cap rejects rather than returning clipped bytes") {
   var rejected=false
   try { capture(speech(350)) } catch(e:RuntimeException) { rejected=e.message=="endpoint_safety_limit" }
   check(rejected) { "safety cap returned command bytes" }
   check(Fixture.stops==1 && Fixture.releases==1)
 }
 test("STOP exact final still wins") {
   Fixture.reset(speech(4)+Frame(tone(),"스톱",true,0.5))
   HandsfreeCapture(context,{true},{},{}).use { capture -> check(capture.nextRequest(true)!!.isEmpty()); check(capture.wasStopped) }
   check(Fixture.stops==1 && Fixture.releases==1)
 }
 test("start rejection never starts or reads microphone") {
   Fixture.reset(speech(2))
   HandsfreeCapture(context,{true},{},{}).use { check(it.nextRequest(true){false}==null) }
   check(Fixture.starts==0 && Fixture.index==0 && Fixture.releases==1)
 }
 test("wake command excludes pre-wake samples and decodes post-wake") {
   val bytes=capture(listOf(Frame(noise(),wake=true))+speech(15)+quiet(35),false)!!
   check(bytes.size==(Fixture.index-1)*3200)
   check(Fixture.decodeCalls==Fixture.index-1)
 }
 test("cleanup failure retains owner and is not successful capture") {
   Fixture.reset(speech(8)+quiet(35)); Fixture.failRelease=true
   var owned=false; var confirmed=true; var rejected=false
   HandsfreeCapture(context,{true},{},{owned=it!=null},{outcome,_->confirmed=outcome.confirmed()}).use {
     try { it.nextRequest(true) } catch(e:IllegalStateException) { rejected=e.message=="release_fixture" }
     check(!it.cleanupConfirmed)
   }
   check(rejected && owned && !confirmed && Fixture.stops==1 && Fixture.releases==1)
 }
 test("decoder endpoint does not cut continued recognized speech") {
   capture(speech(60)+Frame(tone(),"요청 60",true,6.1)+speech(99,61)+quiet(35))
   check(Fixture.index>160)
 }
 test("final endpoint pending is cancelled by resumed speech") {
   capture(speech(8)+Frame(silence(),"요청 7",true,0.8)+quiet(8)+speech(12,8)+quiet(35))
   check(Fixture.index>=43)
 }
 test("same words with advancing word times are new speech evidence") {
   capture(List(160){Frame(tone(),"반복",false,(it+1)/10.0)}+quiet(35))
   check(Fixture.index>160)
 }
 test("empty decoder finals do not turn noise into speech") {
   check(capture(List(110){Frame(noise(),"",true)})!!.isEmpty())
 }
 test("unknown-only recognizer tokens do not turn noise into speech") {
   check(capture(List(110){Frame(noise(),"[unk]",false,(it+1)/10.0)})!!.isEmpty())
 }
 test("wall cap rejects even when AudioRecord returns zero bytes") {
   var rejected=false
   try { capture(listOf(Frame(ShortArray(0),advanceMs=30000))) }
   catch(e:RuntimeException) { rejected=e.message=="endpoint_safety_limit" }
   check(rejected && Fixture.index==1 && Fixture.releases==1)
 }
 test("cancel during command returns no audio and releases microphone") {
   Fixture.reset(speech(10))
   HandsfreeCapture(context,{Fixture.index<3},{},{}).use { check(it.nextRequest(true)==null) }
   check(Fixture.index==3 && Fixture.stops==1 && Fixture.releases==1)
 }
 test("rejection preserves primary and cleanup failures and owner reservation") {
   Fixture.reset(speech(350)); Fixture.failStop=true; Fixture.failRelease=true
   var owned=false; var primary:Throwable?=null
   HandsfreeCapture(context,{true},{},{owned=it!=null},{_,error->primary=error}).use {
     var failure:Throwable?=null
     try { it.nextRequest(true) } catch(e:Throwable) { failure=e }
     check(failure?.message=="endpoint_safety_limit")
     check(failure===primary && failure!!.suppressed.size==2)
     check(!it.cleanupConfirmed)
   }
   check(owned && Fixture.stops==1 && Fixture.releases==1)
 }
 test("new capture resets recognizer evidence") {
   HandsfreeCapture(context,{true},{},{}).use {
     Fixture.reset(speech(10)+quiet(35)); check(it.nextRequest(true)!!.isNotEmpty())
     Fixture.reset(List(110){Frame(noise())}); check(it.nextRequest(true)!!.isEmpty())
   }
 }
 test("ordinary monitor cannot promote unqualified PCM to turn cancellation") {
   Fixture.reset(speech(3)+quiet(3))
   var cancelCalls=0
   val handoff=DuplexTurnHandoff({ cancelCalls++; DuplexTurnHandoff.Cleanup { true } }, { true })
   HandsfreeCapture(context,{true},{},{}).use {
     it.monitorStop({Fixture.index<Fixture.frames.size},null,handoff=handoff) { error("raw STOP forbidden") }
   }
   check(cancelCalls==0 && handoff.scope()==null && Fixture.starts==1 && Fixture.releases==1)
   handoff.close()
 }
 test("actual monitor finally rejects retained turn without a whole endpoint") {
   Fixture.reset(speech(3))
   val scope=PlaybackReference.Scope("r","a",1)
   val handoff=DuplexTurnHandoff({ DuplexTurnHandoff.Cleanup { true } }, { true })
   check(handoff.request(scope,true) && handoff.join(1000)) // explicit test seam only
   HandsfreeCapture(context,{true},{},{}).use {
     // Throw in the actual start effect: capture finally must reject, not manufacture
     // an endpoint or leave a retained maintenance request collecting indefinitely.
     try { it.monitorStop({true},{ throw IllegalStateException("fixture_start") },handoff=handoff) {} }
     catch(expected:IllegalStateException) { check(expected.message=="fixture_start") }
   }
   check(!handoff.collecting() && handoff.take()==null && Fixture.releases==1)
 }
 test("monitor rejects exact raw STOP when owner disallows self-playback tail") {
   Fixture.reset(speech(3)+Frame(tone(),"스톱",true,0.4))
   var called=0
   HandsfreeCapture(context,{true},{},{}).use {
     it.monitorStop({Fixture.index<Fixture.frames.size},null,legacyStopAllowed={false}){called++}
   }
   check(called==0 && Fixture.stops==1 && Fixture.releases==1)
 }
 test("monitor STOP path still owns and cleans microphone independently") {
   Fixture.reset(speech(3)+Frame(tone(),"스톱",true,0.4))
   var called=0
   HandsfreeCapture(context,{true},{},{}).use { it.monitorStop({Fixture.index<Fixture.frames.size},null,legacyStopAllowed={true}){called++} }
   check(called==1 && Fixture.stops==1 && Fixture.releases==1)
 }
 } finally { dir.deleteRecursively() }
 println("RESULT passed=$passed failed=$failed; synthetic JVM doubles, no acoustic/device/model inference")
 check(failed==0) { "$failed connected endpoint checks failed" }
}
