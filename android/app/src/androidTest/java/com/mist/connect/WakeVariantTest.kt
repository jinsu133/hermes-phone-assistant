package com.mist.connect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.io.DataInputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class WakeVariantTest {
 @Test fun replayVariants() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val raw=File(ctx.noBackupFilesDir,"wake-diagnostic-private.pcm")
  assertTrue(raw.length() in 2L..1920000L)
  val hash=MessageDigest.getInstance("SHA-256").digest(raw.readBytes())
  val pcm=ShortArray((raw.length()/2).toInt())
  DataInputStream(raw.inputStream().buffered()).use { r -> for(i in pcm.indices) pcm[i]=r.readShort() }
  val variants=listOf("", "▁HE Y ▁HAD ▁A ▁ME S S @HEY_HERMES", "▁HE Y ▁HAD ▁A ▁ME S S @HEY_HERMES\n▁HE Y ▁HE AD ▁OF ▁ME S S @HEY_HERMES\n▁HE Y ▁HE AD ▁OF ▁US @HEY_HERMES\n▁HE Y ▁HAD ▁A M ▁AS @HEY_HERMES")
  val out=JSONArray();val dir="sherpa-wake"
  for((idx,extra) in variants.withIndex()) {
   val spot=KeywordSpotter(ctx.assets,KeywordSpotterConfig(modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder="$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",decoder="$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",joiner="$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),tokens="$dir/tokens.txt",modelType="zipformer2",numThreads=1,provider="cpu"),keywordsFile="$dir/keywords.txt",keywordsThreshold=0.1f,keywordsScore=1.5f,numTrailingBlanks=2))
   var stream=spot.createStream(extra);val hits=JSONArray()
   try {
    var pos=0
    while(pos<pcm.size) {
     val n=minOf(1600,pcm.size-pos);val block=FloatArray(n) { pcm[pos+it]/32768f }
     stream.acceptWaveform(block,16000);block.fill(0f)
     while(spot.isReady(stream)) {
      spot.decode(stream)
      if(spot.getResult(stream).keyword=="HEY_HERMES") { hits.put((pos+n)/16000.0);stream.release();stream=spot.createStream(extra);break }
     }
     pos+=n
    }
    stream.release();stream=spot.createStream(extra);var silenceHits=0
    repeat(100) {
     stream.acceptWaveform(FloatArray(1600),16000)
     while(spot.isReady(stream)) { spot.decode(stream);if(spot.getResult(stream).keyword.isNotEmpty()) silenceHits++ }
    }
    out.put(JSONObject().put("variant",idx).put("extra_keywords",extra).put("hits",hits).put("silence_hits",silenceHits))
   } finally { stream.release();spot.release() }
  }
  assertArrayEquals(hash,MessageDigest.getInstance("SHA-256").digest(raw.readBytes()))
  File(ctx.filesDir,"wake-variant-result.json").writeText(JSONObject().put("tests",out).put("source_unchanged",true).toString())
  pcm.fill(0)
 }
}
