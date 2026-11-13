package com.mist.connect

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.widget.*
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.sqrt

/** Explicit foreground-only diagnostic. No bridge/network calls. Raw audio is retained until explicit user deletion. */
class WakeDiagnosticActivity : Activity() {
    @Volatile private var active = false
    @Volatile private var busy = false
    @Volatile private var deleteRequested = false
    private lateinit var status: TextView
    private lateinit var start: Button
    private val rawFile get() = File(noBackupFilesDir, "wake-diagnostic-private.pcm")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Never delete retained diagnostic audio on activity creation.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,90,32,32) }
        status = TextView(this).apply {
            textSize=19f
            text=AppText.get(R.string.rt_diag_intro, "60초 로컬 진단\n아직 녹음하지 않습니다.\n\n시작 후 ‘헤이 헤르메스, 티맵 켜 줘’를 자연스럽게 말하고 5초 정도 쉬어 주세요. 중간에 호출어 없는 일반 문장도 말해 주세요.\n\n기기 내부에만 임시 저장합니다. 외부 전송·명령 실행은 하지 않습니다. 원본은 검토가 끝날 때까지 보관하며 직접 삭제할 수 있습니다. 화면을 벗어나면 작업만 중단하고 녹음은 보관합니다.")
        }
        box.addView(status)
        start=Button(this).apply { text=AppText.get(R.string.rt_diag_start, "60초 진단 녹음 시작"); setOnClickListener { begin() } };box.addView(start)
        box.addView(Button(this).apply { text=AppText.get(R.string.rt_diag_stop, "작업 중단 · 녹음 보관"); setOnClickListener { active=false } })
        box.addView(Button(this).apply { text=AppText.get(R.string.rt_diag_delete, "보관한 녹음 삭제"); setOnClickListener {
            android.app.AlertDialog.Builder(this@WakeDiagnosticActivity)
                .setMessage(AppText.get(R.string.rt_diag_delete_confirm, "보관한 진단 원음을 삭제할까요? 삭제 후 다시 분석할 수 없습니다."))
                .setNegativeButton(AppText.get(R.string.cancel, "취소"),null).setPositiveButton(AppText.get(R.string.rt_delete, "삭제")) { _,_->
                    if(busy) { deleteRequested=true;active=false }
                    else { val ok=!rawFile.exists() || rawFile.delete();status.text=if(ok) AppText.get(R.string.rt_diag_deleted, "원본을 삭제했습니다.") else AppText.get(R.string.rt_diag_delete_failed, "삭제하지 못했습니다.") }
                }.show()
        } })
        setContentView(ScrollView(this).apply { addView(box) })
        startService(Intent(this,VoiceRequestService::class.java).setAction(VoiceRequestService.CANCEL_REQUEST))
        stopService(Intent(this,VoiceForegroundService::class.java))
    }
    private fun begin() {
        if(busy) return
        if(rawFile.exists()) { status.text=AppText.get(R.string.rt_diag_exists, "보관 중인 진단 원음이 있습니다. 덮어쓰지 않습니다. 먼저 분석 결과를 확인해 주세요.");return }
        if(!MicrophoneOwner.claim()) { status.text=AppText.get(R.string.rt_diag_mic_busy, "이전 마이크가 정리 중입니다. 잠시 뒤 시작을 눌러 주세요.");return }
        active=true;busy=true;deleteRequested=false;start.isEnabled=false
        Thread({ runDiagnostic() },"wake-private-diagnostic").start()
    }
    private fun show(message:String) { runOnUiThread { if(!isFinishing) status.text=message } }
    private fun runDiagnostic() {
        var recorder:AudioRecord?=null
        var released=false
        var outcome=AppText.get(R.string.rt_diag_cancelled_short, "진단 취소")
        val report=JSONObject()
        try {
            val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(min>0)
            val audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,16000))
            recorder=audio;check(audio.state==AudioRecord.STATE_INITIALIZED)
            audio.startRecording();check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            val block=ShortArray(1600);val begun=SystemClock.elapsedRealtime()
            var samples=0;var peak=0;var lastSecond=-1;val levels=JSONArray()
            DataOutputStream(rawFile.outputStream().buffered()).use { out ->
                while(active && samples<960000 && SystemClock.elapsedRealtime()-begun<60000) {
                    val n=audio.read(block,0,minOf(block.size,960000-samples),AudioRecord.READ_NON_BLOCKING)
                    check(n>=0)
                    if(n==0) { Thread.sleep(10);continue }
                    var sum=0.0
                    for(i in 0 until n) { val v=block[i].toInt();peak=maxOf(peak,kotlin.math.abs(v));sum+=v.toDouble()*v;out.writeShort(v) }
                    samples+=n
                    levels.put(JSONObject().put("end_sample",samples).put("rms",sqrt(sum/n).toInt()))
                    val sec=((SystemClock.elapsedRealtime()-begun)/1000).toInt()
                    if(sec!=lastSecond) { lastSecond=sec;show(AppText.get(R.string.rt_diag_recording, "녹음 중 · ${sec}초 / 최대 60초\n‘헤이 헤르메스, 티맵 켜 줘’\n각 시도 사이에 잠시 쉬어 주세요.\n외부 전송과 명령 실행은 없습니다.", sec)) }
                    block.fill(0)
                }
            }
            audio.stop();audio.release();recorder=null;MicrophoneOwner.release();released=true
            check(active) { "cancelled" }
            show(AppText.get(R.string.rt_diag_analyzing, "녹음 종료 · 기기에서 호출어 감지 시점을 분석하고 있습니다.\n원본은 삭제하지 않고 기기에 보관합니다."))
            val comparisons=JSONArray()
            for ((threshold,score) in listOf(0.25f to 1.5f,0.10f to 1.5f,0.10f to 2.0f)) {
            check(active) { "cancelled" }
            val hits=JSONArray();var consumed=0
            KoreanWakeDetector(applicationContext,{active},threshold,score) { _,_-> }.use { detector ->
                DataInputStream(rawFile.inputStream().buffered()).use { input ->
                    while(active && consumed<samples) {
                        val n=minOf(block.size,samples-consumed)
                        for(i in 0 until n) block[i]=input.readShort()
                        if(detector.accept(block,n)) { hits.put(JSONObject().put("detected_sample",consumed+n));detector.reset() }
                        consumed+=n;block.fill(0)
                    }
                }
            }
            comparisons.put(JSONObject().put("threshold",threshold.toDouble()).put("score",score.toDouble()).put("hits",hits))
            }
            check(active) { "cancelled" }
            report.put("samples",samples).put("sample_rate",16000).put("peak",peak).put("levels",levels).put("comparisons",comparisons)
            outcome=AppText.get(R.string.rt_diag_done, "진단 완료 · ${samples/16000}초\n설정 3가지 비교 완료 · 최대 진폭 $peak\n원본은 기기에 보관 중입니다.\nTelegram에 ‘진단 끝났어요’라고 알려 주세요.", samples/16000, peak)
        } catch(e:Exception) { outcome=if(!active) AppText.get(R.string.rt_diag_cancelled, "진단을 취소했습니다.") else AppText.get(R.string.rt_diag_error, "진단 오류: ${e.javaClass.simpleName}", e.javaClass.simpleName) }
        finally {
            try { recorder?.stop() } catch(_:Exception) {} 
            recorder?.release();if(!released) MicrophoneOwner.release()
            if(deleteRequested) rawFile.delete()
            val deleted=!rawFile.exists()
            report.put("raw_deleted",deleted).put("result",outcome)
            File(filesDir,"wake-diagnostic-result.json").writeText(report.toString())
            active=false;busy=false
            show(outcome+if(deleted) "\n" + AppText.get(R.string.rt_diag_no_original, "보관된 원본이 없습니다.") else "\n" + AppText.get(R.string.rt_diag_kept, "원본 보관 확인 · 검토 후 삭제할 수 있습니다."))
            runOnUiThread { start.isEnabled=true }
        }
    }
    override fun onPause() { active=false;super.onPause() }
}
