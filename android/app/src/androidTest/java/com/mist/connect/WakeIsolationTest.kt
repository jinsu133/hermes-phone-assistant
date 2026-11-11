package com.mist.connect

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.DataInputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class WakeIsolationTest {
    @Test fun isolateRetainedAudio() {
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val raw=File(ctx.noBackupFilesDir,"wake-diagnostic-private.pcm")
        assertTrue(raw.isFile);assertTrue(raw.length() in 2L..1920000L)
        val hash=MessageDigest.getInstance("SHA-256").digest(raw.readBytes())
        val pcm=ShortArray((raw.length()/2).toInt())
        DataInputStream(raw.inputStream().buffered()).use { input -> for(i in pcm.indices) pcm[i]=input.readShort() }
        val report=JSONObject();val tests=JSONArray()
        fun run(label:String,start:Int,end:Int,gain:Float) {
            val hits=JSONArray();var pos=start
            KoreanWakeDetector(ctx,{true},0.1f,1.5f) { _,_-> }.use { detector ->
                while(pos<end) {
                    val n=minOf(1600,end-pos)
                    val block=ShortArray(n) { (pcm[pos+it]*gain).toInt().coerceIn(-32768,32767).toShort() }
                    if(detector.accept(block,n)) { hits.put((pos+n)/16000.0);detector.reset() }
                    pos+=n;block.fill(0)
                }
                repeat(10) { if(detector.accept(ShortArray(1600),1600)) { hits.put((end+(it+1)*1600)/16000.0);detector.reset() } }
            }
            tests.put(JSONObject().put("label",label).put("start_seconds",start/16000.0).put("end_seconds",end/16000.0).put("gain",gain.toDouble()).put("hits",hits))
        }
        run("fresh_second_half",480000,pcm.size,1f)
        run("fresh_second_half_quarter_gain",480000,pcm.size,0.25f)
        // Energy segmentation is NOT transcription. Merge <=700 ms gaps and preserve context.
        val segments=mutableListOf<Pair<Int,Int>>()
        var begin=-1;var last=0;var pos=0
        while(pos<pcm.size) {
            val n=minOf(320,pcm.size-pos);var energy=0.0
            for(i in 0 until n) { val x=pcm[pos+i].toDouble();energy+=x*x }
            if(sqrt(energy/n)>450) { if(begin<0) begin=pos;last=pos+n }
            else if(begin>=0 && pos+n-last>=11200) {
                if(last-begin>=3200) segments.add(maxOf(0,begin-4800) to minOf(pcm.size,last+4800))
                begin=-1
            }
            pos+=n
        }
        if(begin>=0 && last-begin>=3200) segments.add(maxOf(0,begin-4800) to pcm.size)
        for((i,s) in segments.withIndex()) run("isolated_energy_$i",s.first,s.second,1f)
        // Same detector must still detect a known synthetic fixture after retained audio.
        val bytes=inst.context.assets.open("hey-hermes-synthetic.pcm").use { it.readBytes() }
        val synth=ShortArray(bytes.size/2) { ((bytes[2*it].toInt() and 255) or (bytes[2*it+1].toInt() shl 8)).toShort() }
        var positive=false
        KoreanWakeDetector(ctx,{true},0.1f,1.5f) { _,_-> }.use { detector ->
            pos=0
            while(pos<pcm.size) {
                val n=minOf(1600,pcm.size-pos);val block=pcm.copyOfRange(pos,pos+n)
                if(detector.accept(block,n)) detector.reset()
                block.fill(0);pos+=n
            }
            repeat(10) { detector.accept(ShortArray(1600),1600) }
            pos=0
            while(pos<synth.size) {
                val n=minOf(1600,synth.size-pos);val block=synth.copyOfRange(pos,pos+n)
                if(detector.accept(block,n)) { positive=true;detector.reset() }
                block.fill(0);pos+=n
            }
        }
        report.put("tests",tests).put("synthetic_after_recording_detected",positive)
        assertArrayEquals(hash,MessageDigest.getInstance("SHA-256").digest(raw.readBytes()))
        report.put("source_unchanged",true)
        File(ctx.filesDir,"wake-isolation-result.json").writeText(report.toString())
        pcm.fill(0);bytes.fill(0);synth.fill(0)
    }
}
