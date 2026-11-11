package com.mist.connect

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** No credentials, voiceprints, biometrics or transcripts are collected by this activity. */
internal object UnlockCoordinator {
    val gate = UnlockRequestGate()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var visible: UnlockActivity? = null
    fun await(context: Context, active: () -> Boolean): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked) return active()
        val ticket = gate.begin(SystemClock.elapsedRealtime(), 120000)
        main.post {
            if (gate.pending(ticket, SystemClock.elapsedRealtime()) && active()) {
                val intent = Intent(context, UnlockActivity::class.java).putExtra("ticket",ticket)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                try {
                    // AccessibilityService is an Android-supported background activity start exception.
                    val origin = LCAccessibilityService.instance ?: context
                    origin.startActivity(intent)
                } catch (_: RuntimeException) { gate.cancel(ticket) }
            }
        }
        try {
            while (active() && gate.pending(ticket,SystemClock.elapsedRealtime())) Thread.sleep(100)
            return active() && gate.granted(ticket,SystemClock.elapsedRealtime(),!keyguard.isDeviceLocked)
        } finally {
            gate.cancel(ticket)
            main.post { visible?.takeIf { it.ticket == ticket }?.finish() }
        }
    }
}

class UnlockActivity : Activity() {
    var ticket = 0L
        private set
    private val main = Handler(Looper.getMainLooper())
    private var requested = false
    private val timeout = Runnable { UnlockCoordinator.gate.cancel(ticket); finish() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ticket = intent.getLongExtra("ticket",0)
        if (!UnlockCoordinator.gate.pending(ticket,SystemClock.elapsedRealtime())) { finish(); return }
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else { @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
        UnlockCoordinator.visible = this
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48,120,48,48); setBackgroundColor(android.graphics.Color.WHITE)
            addView(TextView(this@UnlockActivity).apply {
                text = "휴대폰 잠금을 해제해 주세요\n\n요청은 기기 메모리에서 최대 2분간 기다립니다. Android에서 직접 인증하면 같은 요청을 이어서 처리합니다.\n\n비밀번호와 지문 정보는 Hermes에 전달되지 않습니다."
                textSize=20f; setTextColor(android.graphics.Color.BLACK)
            })
            addView(Button(this@UnlockActivity).apply { text="요청 취소"; setOnClickListener { UnlockCoordinator.gate.cancel(ticket); finish() } })
        })
        main.postDelayed(timeout,120000)
    }
    override fun onResume() {
        super.onResume()
        if (requested || !UnlockCoordinator.gate.pending(ticket,SystemClock.elapsedRealtime())) return
        requested=true
        main.postDelayed({
            if (!UnlockCoordinator.gate.pending(ticket,SystemClock.elapsedRealtime())) { finish(); return@postDelayed }
            val keyguard=getSystemService(KeyguardManager::class.java)
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    UnlockCoordinator.gate.complete(ticket,SystemClock.elapsedRealtime(),!keyguard.isDeviceLocked)
                    finish()
                }
                override fun onDismissCancelled() { UnlockCoordinator.gate.cancel(ticket); finish() }
                override fun onDismissError() { UnlockCoordinator.gate.cancel(ticket); finish() }
            })
        },250)
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (UnlockCoordinator.visible === this) UnlockCoordinator.visible=null
        if (UnlockCoordinator.gate.pending(ticket,SystemClock.elapsedRealtime())) UnlockCoordinator.gate.cancel(ticket)
        super.onDestroy()
    }
}
