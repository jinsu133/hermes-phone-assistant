package com.mist.connect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.io.DataInputStream
import org.json.JSONObject
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class CaptureRepairTest {
 @Test fun replayRequestBoundaries() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val raw=File(ctx.noBackupFilesDir,"wake-diagnostic-private.pcm")
  assertTrue(raw.length() in 2L..1920000L)
  val pcm=ShortArray((raw.length()/2).toInt())
  DataInputStream(raw.inputStream().buffered()).use { r -> for(i in pcm.indices) pcm[i]=r.readShort() }
  var wakes=0;var stops=0;var captures=0;var silences=0;var maxDecodeMs=0L
  val events=JSONArray()
  KoreanWakeDetector(ctx,{true}) { _,_-> }.use { detector ->
   val t=System.nanoTime();detector.prepareStop();val loadMs=(System.nanoTime()-t)/1000000
   var triggered=false;var gate=SpeechEndGate();var pos=0
   while(pos<pcm.size) {
    val n=minOf(1600,pcm.size-pos);val block=pcm.copyOfRange(pos,pos+n)
    if(!triggered) {
     if(detector.accept(block,n)) { wakes++;triggered=true;gate=SpeechEndGate();events.put(JSONObject().put("wake",(pos+n)/16000.0)) }
    } else {
     val start=System.nanoTime();val stopped=detector.acceptStop(block,n)
     maxDecodeMs=maxOf(maxDecodeMs,(System.nanoTime()-start)/1000000)
     if(stopped || gate.accept(block,n)) {
      if(stopped) stops++ else if(gate.hasSpeech()) captures++ else silences++
      events.put(JSONObject().put("end",(pos+n)/16000.0).put("stopped",stopped).put("has_speech",gate.hasSpeech()))
      detector.reset();triggered=false
     }
    }
    pos+=n;block.fill(0)
   }
   File(ctx.filesDir,"capture-repair-result.json").writeText(JSONObject().put("wakes",wakes).put("captures",captures).put("silences",silences).put("stops",stops).put("preload_ms",loadMs).put("max_stop_decode_ms",maxDecodeMs).put("events",events).toString())
  }
  pcm.fill(0)
  assertEquals("No stop utterance in approved retained recording",0,stops)
 }
}
