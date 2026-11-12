package com.mist.connect

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/** Debug-only harness: renders the production Capsule and Mascot on Android.
 * It does not create a TYPE_APPLICATION_OVERLAY window, start the microphone, grant
 * permissions, connect to services, or indicate a live service state. During the speaking
 * case it publishes a simulated 9-second playback so the answer scroll can be inspected.
 */
internal class OverlayRenderPreviewActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var windowAdapter: AndroidStatusOverlayWindow
    private lateinit var capsule: View
    private var simulated: PlaybackProgress.Source? = null
    private val answer = "오늘 일정은 오후 두 시 팀 회의와 네 시 치과 예약입니다. " +
        "회의 자료는 메일함의 첫 번째 메시지에 있습니다. 치과까지는 지하철로 이십 분 " +
        "정도 걸리니 세 시 반에는 출발하세요. 저녁에는 비 소식이 있어 우산을 챙기시는 게 " +
        "좋겠습니다. 더 궁금하신 점이 있나요?"
    private val cases = listOf(
        Triple(UiPhase.WAKE_WAIT, "호출어를 기다리고 있어요.", 3000L),
        Triple(UiPhase.CAPTURING, "말씀을 듣고 있어요.", 3000L),
        Triple(UiPhase.WAITING_REPLY, "요청을 확인하고 있어요.", 3000L),
        Triple(UiPhase.PLAYING, answer, 10000L),
        Triple(UiPhase.FOLLOW_UP, "이어 말씀해 주세요.", 3000L)
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(65, 71, 78)) }
        setContentView(root)
        windowAdapter = AndroidStatusOverlayWindow(applicationContext)
        val klass = AndroidStatusOverlayWindow::class.java
        val build = klass.getDeclaredMethod("build").apply { isAccessible = true }
        capsule = build.invoke(windowAdapter) as View
        klass.getDeclaredField("view").apply { isAccessible = true }.set(windowAdapter, capsule)
        // Window alpha in the real adapter is at most 0.75; apply it to this real view.
        capsule.alpha = AndroidStatusOverlayWindow.WINDOW_ALPHA_CEILING
        root.addView(capsule, FrameLayout.LayoutParams(windowAdapter.cardWidth(), windowAdapter.cardHeight(),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (resources.displayMetrics.heightPixels / 4 - windowAdapter.cardHeight() / 2).coerceAtLeast(0)
        })
        val setText = klass.getDeclaredMethod("updateText", String::class.java).apply { isAccessible = true }
        var index = 0
        val next = object : Runnable {
            override fun run() {
                val (phase, text, hold) = cases[index % cases.size]
                simulated?.let { PlaybackProgress.end(it) }; simulated = null
                val speaking = phase == UiPhase.PLAYING
                windowAdapter.finalPreview(speaking)
                windowAdapter.speaking(speaking)
                if (speaking) {
                    val start = SystemClock.uptimeMillis()
                    simulated = object : PlaybackProgress.Source {
                        override fun positionMs() = SystemClock.uptimeMillis() - start
                        override fun durationMs() = 9000L
                    }.also { PlaybackProgress.begin(it) }
                }
                setText.invoke(windowAdapter, text)
                windowAdapter.visualPhase(phase)
                index++
                handler.postDelayed(this, hold)
            }
        }
        handler.postDelayed(next, 1000L)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        simulated?.let { PlaybackProgress.end(it) }
        windowAdapter.stopPreview()
        super.onDestroy()
    }
}
