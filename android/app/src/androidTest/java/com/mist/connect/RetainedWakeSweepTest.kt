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

/** Explicit retained-file replay. Never records, uploads or deletes the source. */
@RunWith(AndroidJUnit4::class)
class RetainedWakeSweepTest {
    @Test fun replayThresholds() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val raw=File(ctx.noBackupFilesDir,"wake-diagnostic-private.pcm")
        assertTrue("User recording missing",raw.isFile)
        assertTrue(raw.length() in 2L..1920000L)
        fun digest():String = MessageDigest.getInstance("SHA-256").digest(raw.readBytes()).joinToString("") { "%02x".format(it) }
        val before=digest()
        val count=(raw.length()/2).toInt()
        val results=JSONArray()
        for(threshold in listOf(0.25f,0.10f,0.05f,0.02f,0.01f)) {
            val hits=JSONArray();var consumed=0
            val block=ShortArray(1600)
            KoreanWakeDetector(ctx,{true},threshold,1.5f) { _,_-> }.use { detector ->
                DataInputStream(raw.inputStream().buffered()).use { input ->
                    while(consumed<count) {
                        val n=minOf(block.size,count-consumed)
                        for(i in 0 until n) block[i]=input.readShort()
                        if(detector.accept(block,n)) { hits.put(consumed+n);detector.reset() }
                        consumed+=n;block.fill(0)
                    }
                }
            }
            results.put(JSONObject().put("threshold",threshold.toDouble()).put("score",1.5).put("hit_samples",hits))
        }
        assertEquals(before,digest())
        File(ctx.filesDir,"wake-threshold-sweep.json").writeText(JSONObject()
            .put("samples",count).put("sample_rate",16000).put("source_unchanged",true)
            .put("comparisons",results).toString())
    }
}
