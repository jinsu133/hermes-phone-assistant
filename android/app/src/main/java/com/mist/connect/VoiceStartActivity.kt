package com.mist.connect

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** A visible user gesture grants Android's while-in-use microphone startup. */
class VoiceStartActivity : Activity() {
    private var launched = false
    override fun onCreate(state: Bundle?) { super.onCreate(state) }
    override fun onResume() {
        super.onResume()
        if (launched) return
        launched = true
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 710); return }
        begin()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) begin() else finish()
    }
    private fun begin() {
        val prefs = getSharedPreferences("handsfree", MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false)) {
            AlertDialog.Builder(this).setTitle("헤이 헤르메스 호출어 듣기")
                .setMessage("ON 동안 마이크와 로컬 음성 모델이 동작합니다. 대기 음성은 저장하거나 전송하지 않습니다. ‘헤이 헤르메스’가 감지되면 요청 녹음만 Hermes STT로 전송하고 연결한 대화와 설정된 TTS를 사용합니다. 안전한 모델 적용을 호스트가 보장하지 못하면 시작하지 않습니다. 답변 후 30초는 호출어 없이 이어 말할 수 있습니다. 무음이면 호출어 대기로 돌아갑니다. ‘스탑’은 대화를 종료하고 OFF는 마이크와 재생을 중단합니다. 잠겨 있으면 Android 인증 화면을 표시하고 최대 2분 기다립니다. 인증 후 요청을 이어서 처리하며, 취소하면 요청을 폐기합니다. 음성은 본인 인증 수단이 아닙니다.")
                .setNegativeButton("취소") { _, _ -> finish() }
                .setPositiveButton("동의하고 ON") { _, _ -> prefs.edit().putBoolean("consent", true).apply(); launch() }
                .setOnCancelListener { finish() }.show()
        } else launch()
    }
    private fun launch() {
        try {
            if (getSharedPreferences("handsfree", MODE_PRIVATE).getBoolean("control_consent", false))
                startForegroundService(Intent(this, McpService::class.java))
            startForegroundService(Intent(this, VoiceRequestService::class.java).setAction(VoiceRequestService.START_HANDSFREE))
        }
        catch (_: RuntimeException) { android.widget.Toast.makeText(this, "마이크를 시작할 수 없습니다. 앱을 열어 권한을 확인해 주세요.", android.widget.Toast.LENGTH_LONG).show() }
        finish()
    }
}
