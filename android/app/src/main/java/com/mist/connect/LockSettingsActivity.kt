package com.mist.connect

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-driven settings navigation only. Never reads credentials or changes trust policy. */
class LockSettingsActivity : Activity() {
    private lateinit var state: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 48)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        state = TextView(this).apply { textSize = 20f; setTextColor(android.graphics.Color.BLACK) }
        layout.addView(state)
        layout.addView(TextView(this).apply {
            text = "\nExtend Unlock 설정 안내\n\n삼성 설정에서 ‘잠금화면 및 AOD → Extend Unlock’을 찾거나, 설정 검색에 ‘Extend Unlock’을 입력하세요. 메뉴 이름은 기기에 따라 다릅니다.\n\n신뢰할 수 있는 기기나 장소는 직접 선택해야 합니다. PIN·패턴은 Android 설정에서 직접 입력하며 Hermes는 읽거나 저장하지 않습니다.\n\n이 기능은 위젯 OFF와 자동 연동되지 않습니다. 신뢰 조건에서는 다른 사람도 휴대폰을 사용할 수 있습니다. 최초 인증과 주기적인 재인증이 필요합니다.\n\nHermes는 현재 잠금 여부만 확인하며, Extend Unlock 활성 여부나 남은 시간은 표시하지 않습니다.\n"
            textSize = 16f; setTextColor(android.graphics.Color.BLACK)
        })
        layout.addView(Button(this).apply {
            text = "Android 보안 설정 열기"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        })
        layout.addView(Button(this).apply { text = "돌아가기"; setOnClickListener { finish() } })
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
    override fun onResume() {
        super.onResume()
        val kg = getSystemService(KeyguardManager::class.java)
        state.text = if (kg.isDeviceLocked) "현재 상태: 잠금 해제 필요" else "현재 상태: 잠금 해제됨"
        VoiceWidget.update(this)
    }
}
