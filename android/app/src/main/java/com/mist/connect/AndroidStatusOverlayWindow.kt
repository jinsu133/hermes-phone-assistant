package com.mist.connect

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** One noninteractive window, application context only. Removal uncertainty poisons
 * this adapter rather than permitting a replacement window. No gesture/action API.
 *
 * Visual contract follows the reviewed capsule design in `docs/VOICE_OVERLAY.md`
 * (rounded dark capsule, three vertical bars on the left, text on the right) with the
 * owner-specified position change: the capsule centre sits at 2/8 of the display height
 * instead of the design's top 8dp gap. */
internal class AndroidStatusOverlayWindow(app: Context) : OptionalOverlayController.Window {
    companion object {
        const val CARD_WIDTH_DP = 340
        const val CORNER_DP = 28
        const val PAD_H_DP = 16
        const val PAD_V_DP = 10
        const val BARS_W_DP = 28
        const val BARS_H_DP = 30
        const val TEXT_SP = 14f
        const val ORBIT_PERIOD_MS = 2000L
        const val ORBIT_DOT_DP = 6
        const val BORDER_DP = 1.5f
        const val BACKGROUND_ALPHA = 0.72f
        const val WINDOW_ALPHA_CEILING = 0.75f
        /** Owner-confirmed anchor: 2/8 from the top of the display. */
        const val ANCHOR_NUMERATOR = 2
        const val ANCHOR_DENOMINATOR = 8
        const val WINDOW_TITLE = "Hermes public voice status"
    }

    private val context = if (Build.VERSION.SDK_INT >= 30) {
        val display = checkNotNull(app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else app.applicationContext
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: Capsule? = null
    private var label: TextView? = null
    private var bars: Bars? = null
    private var showingFinal = false
    private var poisoned = false
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    override fun show(title: String): Boolean {
        if (poisoned) return false
        val existing = view
        if (existing != null) {
            updateText(title)
            return true
        }
        val declared = dp(CARD_WIDTH_DP)
            .coerceAtMost(context.resources.displayMetrics.widthPixels - dp(16))
            .coerceAtLeast(dp(120))
        val capsule = build()
        updateText(title)
        capsule.measure(
            View.MeasureSpec.makeMeasureSpec(declared, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val height = capsule.measuredHeight.coerceAtLeast(1)
        view = capsule // retain ownership before add, including partial failure
        try { manager.addView(capsule, params(declared, height)) } catch (_: RuntimeException) {
            poisoned = true; hide(); return false
        }
        return true
    }

    private fun updateText(text: String) {
        val target = label ?: return
        if (target.text.toString() == text) return
        target.animate().cancel()
        target.translationY = 0f
        target.alpha = 1f
        target.text = text
        if (showingFinal) {
            target.animate().translationY(-dp(12).toFloat()).alpha(0f)
                .setStartDelay(4500L).setDuration(700L).start()
        }
    }

    private fun build(): Capsule = Capsule(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PAD_H_DP), dp(PAD_V_DP), dp(PAD_H_DP), dp(PAD_V_DP))
        isClickable = false; isLongClickable = false; isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Background alpha is independent of the window touch-safety alpha, as in the design.
        background = GradientDrawable().apply {
            setColor(Color.argb((BACKGROUND_ALPHA * 255).toInt(), 24, 24, 24))
            cornerRadius = dp(CORNER_DP).toFloat()
        }
        bars = Bars(context)
        addView(bars, LinearLayout.LayoutParams(dp(BARS_W_DP), dp(BARS_H_DP)))
        val lines = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = TEXT_SP
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            isClickable = false; isLongClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextIsSelectable(false)
        }.also { lines.addView(it) }
        addView(lines, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun params(width: Int, height: Int): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val anchor = metrics.heightPixels * ANCHOR_NUMERATOR / ANCHOR_DENOMINATOR
        // API 30+ uses full-screen layout, so the anchor is measured from the real display edge.
        val frameTop = if (Build.VERSION.SDK_INT >= 30) 0 else statusBarHeight()
        val y = (anchor - height / 2 - frameTop).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.y = y
            // Android 12+ untrusted-overlay touch policy: stay below the obscuring threshold.
            alpha = if (Build.VERSION.SDK_INT >= 31)
                minOf(WINDOW_ALPHA_CEILING, context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
            else WINDOW_ALPHA_CEILING
            if (Build.VERSION.SDK_INT >= 30) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                setFitInsetsTypes(WindowInsets.Type.systemBars())
            }
            setTitle(WINDOW_TITLE)
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) return 0
        return try { context.resources.getDimensionPixelSize(id) } catch (_: RuntimeException) { 0 }
    }

    override fun hide() {
        val owned = view ?: return
        label?.animate()?.cancel()
        try {
            manager.removeViewImmediate(owned)
            view = null
            label = null
        } catch (_: RuntimeException) {
            // Keep the handle and never add another window when removal is uncertain.
            poisoned = true
        }
    }

    /** Presentation-only hints from the controller; they never gate voice authority. */
    override fun speaking(active: Boolean) { bars?.speaking(active) }
    override fun recognizing(active: Boolean) { }
    override fun finalPreview(active: Boolean) { showingFinal = active }
    override fun visualPhase(phase: UiPhase) { view?.visualPhase(phase) }

    /** Animated, state-colored conic border; owns no voice or permission authority. */
    private inner class Capsule(context: Context) : LinearLayout(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, 2f * context.resources.displayMetrics.density)
        }
        private val rect = RectF()
        private val rotation = Matrix()
        private var orbit: ValueAnimator? = null
        private var phase = 0f
        private var tint = Color.rgb(94, 165, 255)

        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun visualPhase(state: UiPhase) {
            val next = when (state) {
                UiPhase.CAPTURING -> Color.rgb(57, 224, 188)
                UiPhase.SUBMITTING, UiPhase.WAITING_REPLY -> Color.rgb(175, 118, 255)
                UiPhase.PLAYING -> Color.rgb(255, 168, 75)
                UiPhase.FOLLOW_UP -> Color.rgb(101, 217, 255)
                else -> Color.rgb(94, 165, 255)
            }
            if (next != tint) { tint = next; invalidate() }
            if (orbit != null) return
            orbit = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ORBIT_PERIOD_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (orbit == null) return
            val inset = border.strokeWidth * 1.5f
            rect.set(inset, inset, width - inset, height - inset)
            val shader = SweepGradient(width / 2f, height / 2f,
                intArrayOf(Color.TRANSPARENT, tint, Color.WHITE, tint, Color.TRANSPARENT), null)
            rotation.setRotate(phase * 360f, width / 2f, height / 2f)
            shader.setLocalMatrix(rotation)
            border.shader = shader
            border.setShadowLayer(dp(4).toFloat(), 0f, 0f, tint)
            canvas.drawRoundRect(rect, dp(CORNER_DP).toFloat(), dp(CORNER_DP).toFloat(), border)
        }

        override fun onDetachedFromWindow() {
            orbit?.cancel(); orbit = null; border.clearShadowLayer(); super.onDetachedFromWindow()
        }
    }

    /** Three vertical bars; they wave only while audio is actually playing. */
    private class Bars(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeCap = Paint.Cap.ROUND
        }
        private var animation: ValueAnimator? = null
        private var phase = 0f
        fun speaking(active: Boolean) {
            if (!active) { animation?.cancel(); animation = null; phase = 0f; invalidate(); return }
            if (animation != null) return
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 640
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.strokeWidth = width / 9f
            for (i in 0..2) {
                val wave = if (animation == null) 0.35f else
                    (0.3 + 0.6 * kotlin.math.abs(kotlin.math.sin((phase * 2.0 + i * 0.6) * Math.PI))).toFloat()
                val half = height * wave / 2f
                val x = width * (i + 1) / 4f
                canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
            }
        }
        override fun onDetachedFromWindow() { speaking(false); super.onDetachedFromWindow() }
    }
}
