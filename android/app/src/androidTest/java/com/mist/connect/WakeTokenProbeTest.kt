package com.mist.connect

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.io.DataInputStream
import org.json.JSONObject
import org.json.JSONArray

/** Local diagnostic model hypotheses, NOT trusted Korean transcripts. */
@RunWith(AndroidJUnit4::class)
class WakeTokenProbeTest {
    @Test fun inspectModelHypotheses() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val raw=File(ctx.noBackupFilesDir,"wake-diagnostic-private.pcm")
        assertTrue(raw.isFile);assertTrue(raw.length() in 2L..1920000L)
        val pcm=ShortArray((raw.length()/2).toInt())
        DataInputStream(raw.inputStream().buffered()).use { input -> for(i in pcm.indices) pcm[i]=input.readShort() }
        val dir="sherpa-wake"
        val rec=OnlineRecognizer(ctx.assets,OnlineRecognizerConfig(
            modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(
                encoder="$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder="$dir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                joiner="$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
            ),tokens="$dir/tokens.txt",modelType="zipformer2",numThreads=1,provider="cpu",debug=false),
            enableEndpoint=false,decodingMethod="greedy_search"
        ))
        val tests=JSONObject(File(ctx.filesDir,"wake-isolation-result.json").readText()).getJSONArray("tests")
        val out=JSONArray()
        try {
            for(i in 0 until tests.length()) {
                val test=tests.getJSONObject(i)
                if(!test.getString("label").startsWith("isolated_energy_")) continue
                val start=(test.getDouble("start_seconds")*16000).toInt()
                val end=minOf(pcm.size,(test.getDouble("end_seconds")*16000).toInt())
                val stream=rec.createStream()
                try {
                    var pos=start
                    while(pos<end) {
                        val n=minOf(1600,end-pos);val block=FloatArray(n) { pcm[pos+it]/32768f }
                        stream.acceptWaveform(block,16000);block.fill(0f)
                        while(rec.isReady(stream)) rec.decode(stream)
                        pos+=n
                    }
                    stream.acceptWaveform(FloatArray(16000),16000)
                    while(rec.isReady(stream)) rec.decode(stream)
                    val result=rec.getResult(stream)
                    out.put(JSONObject().put("start",start/16000.0).put("end",end/16000.0)
                        .put("model_hypothesis",result.text).put("tokens",JSONArray(result.tokens.toList()))
                        .put("kws_hits",test.getJSONArray("hits")))
                } finally { stream.release() }
            }
            File(ctx.filesDir,"wake-token-probe.json").writeText(JSONObject().put("hypotheses",out).toString())
        } finally { rec.release();pcm.fill(0) }
    }
}
