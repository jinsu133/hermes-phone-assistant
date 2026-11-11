package com.mist.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandsfreeDeviceTest {
    @Test fun localStopFromSyntheticPcm() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val raw = instrument.context.assets.open("phone-stop.pcm").use { it.readBytes() }
        val audio = ShortArray(raw.size/2+64000)
        for (i in 0 until raw.size/2) audio[i] = ((raw[i*2].toInt() and 255) or (raw[i*2+1].toInt() shl 8)).toShort()
        var stopped=false
        val texts=linkedSetOf<String>()
        KoreanWakeDetector(instrument.targetContext, { true }) { text, _ -> if(text.isNotBlank()) texts.add(text) }.use { d ->
            var pos=0
            while(pos<audio.size) { val n=minOf(1600,audio.size-pos); d.acceptStop(audio.copyOfRange(pos,pos+n),n); stopped=stopped||d.stopped; pos+=n }
        }
        assertTrue("Synthetic stop not recognized: $texts", stopped)
    }
    @Test fun localWakeFromSyntheticPcm() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val raw = instrument.context.assets.open("hermes-wake-positive.pcm").use { it.readBytes() }
        val audio = ShortArray(raw.size/2+32000)
        for (i in 0 until raw.size/2) audio[i] = ((raw[i*2].toInt() and 255) or (raw[i*2+1].toInt() shl 8)).toShort()
        var hits=0
        var falseStop=false
        val texts=linkedSetOf<String>()
        KoreanWakeDetector(instrument.targetContext, { true }) { text, _ -> if(text.isNotBlank()) texts.add(text) }.use { d ->
            var pos=0
            while(pos<audio.size) { val n=minOf(1600,audio.size-pos); if(d.accept(audio.copyOfRange(pos,pos+n),n))hits++; falseStop=falseStop||d.acceptStop(audio.copyOfRange(pos,pos+n),n); pos+=n }
        }
        android.util.Log.i("HandsfreeDeviceTest", "SYNTHETIC_WAKE hits=$hits texts=$texts")
        assertTrue("Synthetic wake was not recognized: $texts", hits>0)
        assertFalse("Ordinary greeting falsely stopped the conversation", falseStop)
    }
}
