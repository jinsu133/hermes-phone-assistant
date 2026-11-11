package com.mist.connect

import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor

/** Metadata-only capability probe. Never starts listening or requests a model download. */
class AndroidNativeSttSupportTest {
    @Test fun capabilitiesWithoutRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = JSONObject()
        instrumentation.runOnMainSync {
            report.put("recognition_available", SpeechRecognizer.isRecognitionAvailable(context))
            report.put("on_device_available", SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            report.put("default_service", Settings.Secure.getString(context.contentResolver, "voice_recognition_service"))
        }
        for (onDevice in listOf(true, false)) {
            val row = JSONObject().put("on_device", onDevice)
            val latch = CountDownLatch(1)
            var recognizer: SpeechRecognizer? = null
            var pipes: Array<ParcelFileDescriptor>? = null
            instrumentation.runOnMainSync {
                try {
                    if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        row.put("available", false)
                        latch.countDown()
                    } else {
                        recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        else SpeechRecognizer.createSpeechRecognizer(context, ComponentName.unflattenFromString(
                            Settings.Secure.getString(context.contentResolver, "voice_recognition_service"))!!)
                        pipes = ParcelFileDescriptor.createPipe()
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipes!![0])
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                        recognizer!!.checkRecognitionSupport(intent, Executor { it.run() }, object: RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                row.put("support_checked", true)
                                row.put("installed_on_device_languages", JSONArray(support.installedOnDeviceLanguages))
                                row.put("pending_on_device_languages", JSONArray(support.pendingOnDeviceLanguages))
                                row.put("supported_on_device_languages", JSONArray(support.supportedOnDeviceLanguages))
                                row.put("online_languages", JSONArray(support.onlineLanguages))
                                latch.countDown()
                            }
                            override fun onError(error: Int) {
                                row.put("support_error", error)
                                latch.countDown()
                            }
                        })
                    }
                } catch (e: Exception) {
                    row.put("error_type", e.javaClass.simpleName)
                    latch.countDown()
                }
            }
            row.put("completed", latch.await(12, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { recognizer?.destroy() }
            pipes?.forEach { it.close() }
            report.put(if (onDevice) "on_device" else "default", row)
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("android_stt_support", report.toString()) })
    }
}
