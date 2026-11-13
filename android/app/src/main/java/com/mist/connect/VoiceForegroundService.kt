package com.mist.connect

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded local microphone/wake probe. No cloud STT, conversation or actions. */
class VoiceForegroundService : Service() {
    companion object {
        const val STOP = "com.mist.connect.hermeslab.STOP_VOICE_PROBE"
        const val CHANNEL = "voice_probe"
        const val NOTIFICATION = 301
        val transcript = TransientTranscript { SystemClock.elapsedRealtime() }
        @Volatile var status = AppText.get(R.string.rt_probe_off, "음성 시험 OFF")
            private set
    }
    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: AudioRecord? = null
    private val deadline = Runnable { stopSelf() }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (running.get()) return START_NOT_STICKY
        if (intent.action != "START_PROBE" && intent.action != "START_WAKE_PROBE") {
            stopSelf(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = AppText.get(R.string.rt_probe_mic_needed, "마이크 권한 필요"); stopSelf(); return START_NOT_STICKY
        }
        if (!MicrophoneOwner.claim()) { stopSelf(); return START_NOT_STICKY }
        val wake = intent.action == "START_WAKE_PROBE"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, AppText.get(R.string.rt_probe_channel, "제한된 음성 시험"), NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getService(this, 301,
            Intent(this, VoiceForegroundService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if(wake) AppText.get(R.string.rt_probe_wake_title, "Hermes 로컬 호출어 시험 · 최대 60초") else AppText.get(R.string.rt_probe_mic_title, "Hermes 마이크 시험 · 최대 60초"))
            .setContentText(AppText.get(R.string.rt_probe_text, "대화 미연결 · 음성 저장/전송 없음 · 누르면 중단"))
            .setContentIntent(pending).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, note)
        } catch (_: RuntimeException) {
            MicrophoneOwner.release(); status = AppText.get(R.string.rt_probe_fg_denied, "전경 마이크 시작 거절됨"); stopSelf(); return START_NOT_STICKY
        }
        running.set(true)
        transcript.setRecording(wake)
        status = if(wake) AppText.get(R.string.rt_probe_model, "로컬 모델 준비 중 · 대화 미연결") else AppText.get(R.string.rt_probe_mic_init, "마이크 초기화 중")
        // Include initialization in the hard scheduling bound; capture has its own elapsed check.
        main.postDelayed(deadline, 60_000)
        Thread({ capture(wake, startId) }, "phone-mic-probe").start()
        return START_NOT_STICKY
    }

    private fun capture(wake: Boolean, startId: Int) {
        var audio: AudioRecord? = null
        var detector: KoreanWakeDetector? = null
        var total = 0L
        var peak = 0
        var hits = 0
        var failure = ""
        var decodeNanos = 0L
        var blocks = 0
        val started = SystemClock.elapsedRealtime()
        try {
            if(wake) detector=KoreanWakeDetector(applicationContext, { running.get() }) { text, complete ->
                if(running.get()) transcript.update(text, complete)
            }
            if(!running.get()) return
            val rate=16000
            val minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum>0)
            audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,rate,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,16000))
            check(audio.state==AudioRecord.STATE_INITIALIZED)
            recorder=audio
            if(!running.get()) return
            audio.startRecording()
            check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            val buffer=ShortArray(1600)
            while(running.get() && SystemClock.elapsedRealtime()-started<60_000) {
                val n=audio.read(buffer,0,buffer.size,AudioRecord.READ_NON_BLOCKING)
                check(n>=0)
                if(n>0) {
                    total+=n
                    for(i in 0 until n) peak=maxOf(peak,kotlin.math.abs(buffer[i].toInt()))
                    val t=System.nanoTime()
                    if(running.get() && detector?.accept(buffer,n)==true) hits++
                    decodeNanos+=System.nanoTime()-t
                    blocks++
                    buffer.fill(0)
                    if(running.get()) status=(if(wake) AppText.get(R.string.rt_probe_wake_progress, "로컬 호출어 시험: ${total/rate}초 · 감지 ${hits}회 · 대화 미연결", total/rate, hits) else AppText.get(R.string.rt_probe_mic_progress, "마이크 시험: ${total/rate}초 · 감지 ${hits}회 · 대화 미연결", total/rate, hits))
                } else Thread.sleep(20)
            }
        } catch(e: Exception) { if(running.get()) failure=e.javaClass.simpleName }
        finally {
            running.set(false)
            transcript.setRecording(false)
            try { audio?.stop() } catch (_: RuntimeException) { }
            audio?.release(); recorder=null
            try { detector?.close() } catch(e: Exception) { failure=e.javaClass.simpleName }
            val elapsed=SystemClock.elapsedRealtime()-started
            status=if(failure.isNotEmpty() || total==0L) AppText.get(R.string.rt_probe_failed, "시험 종료 · 입력/모델 확인 필요: $failure", failure) else
                AppText.get(R.string.rt_probe_done, "시험 종료: ${total/16000}초, 최대 진폭 $peak, 호출어 ${hits}회 · 음성 저장/전송 없음", total/16000, peak, hits)
            getSharedPreferences("voice_probe",MODE_PRIVATE).edit()
                .putString("result",status).putLong("samples",total).putInt("peak",peak)
                .putInt("wake_hits",hits).putBoolean("wake_mode",wake)
                .putLong("elapsed_ms",elapsed).putLong("decode_ms",decodeNanos/1_000_000)
                .putInt("blocks",blocks).putString("error",failure).apply()
            MicrophoneOwner.release()
            main.post { stopSelf(startId) }
        }
    }
    override fun onDestroy() {
        running.set(false)
        transcript.setRecording(false)
        main.removeCallbacks(deadline)
        try { recorder?.stop() } catch (_: RuntimeException) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
