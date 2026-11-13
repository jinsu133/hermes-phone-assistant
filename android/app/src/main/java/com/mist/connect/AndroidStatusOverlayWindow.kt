package com.mist.connect

import android.animation.TimeAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** One noninteractive window, application context only. Removal uncertainty poisons
 * this adapter rather than permitting a replacement window. No gesture/action API.
 *
 * The window has one fixed size for a display (D-013): state, status text and answer length
 * never resize or move it. A long answer scrolls inside a three-line viewport, driven by the
 * position of the reply audio that is actually playing (D-014); it pauses when playback stalls
 * and stops when playback ends or is replaced. The capsule centre sits at 2/8 of the display.
 */
internal class AndroidStatusOverlayWindow(app: Context) : OptionalOverlayController.Window {
    companion object {
        const val CARD_WIDTH_DP = 340
        const val CARD_HEIGHT_DP = 96
        const val CORNER_DP = 28
        const val PAD_H_DP = 14
        const val PAD_V_DP = 10
        const val MASCOT_DP = 72
        const val TEXT_SP = 14f
        const val HINT_SP = 11f
        const val FADE_DP = 14
        const val SCROLL_TICK_MS = 40L
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
    private var hint: TextView? = null
    private var mascot: Mascot? = null
    private var cardBackground: GradientDrawable? = null
    /** Theme changes recolour the live card; the state glow keeps its own colours. */
    private val themeListener: () -> Unit = {
        val palette = AppTheme.palette
        cardBackground?.setColor(palette.overlayMatte)
        label?.setTextColor(palette.overlayInk)
        hint?.setTextColor(palette.overlayInk)
        mascot?.invalidate()
        view?.invalidate()
    }
    private var showingFinal = false
    private var playing = false
    private var follower: Runnable? = null
    private var poisoned = false
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** Fixed size for this display; also used by the debug preview. */
    internal fun cardWidth(): Int = OverlaySizing.width(context.resources.displayMetrics.widthPixels,
        dp(CARD_WIDTH_DP), dp(16))
    internal fun cardHeight(): Int = dp(CARD_HEIGHT_DP)

    override fun show(title: String): Boolean {
        if (poisoned) return false
        if (view != null) {
            updateText(title)
            return true // Only pixels change; neither window dimensions nor position changes.
        }
        val capsule = build()
        updateText(title)
        view = capsule // retain ownership before add, including partial failure
        AppTheme.observe(themeListener)
        try { manager.addView(capsule, params(cardWidth(), cardHeight())) } catch (_: RuntimeException) {
            poisoned = true; hide(); return false
        }
        return true
    }

    private fun updateText(text: String) {
        val target = label ?: return
        if (target.text.toString() == text) return
        stopFollowing()
        target.scrollTo(0, 0)
        target.text = text // Never truncated: overflow scrolls within the fixed viewport.
        if (showingFinal) startFollowing()
    }

    /** Moves the answer with the played fraction of the reply audio; holds still otherwise. */
    private fun startFollowing() {
        val target = label ?: return
        val task = object : Runnable {
            override fun run() {
                if (label !== target || follower !== this) return
                val fraction = if (playing) PlaybackProgress.fraction() else -1f
                if (fraction >= 0f) {
                    val layout = target.layout
                    if (layout != null) {
                        val viewport = target.height - target.totalPaddingTop - target.totalPaddingBottom
                        val goal = OverlayScroll.offset(fraction, layout.height, viewport, target.lineHeight)
                        val step = goal - target.scrollY
                        // Ease toward the goal so lines glide upward instead of jumping.
                        if (step != 0) target.scrollTo(0, target.scrollY +
                            if (kotlin.math.abs(step) <= 1) step else step / 4)
                    }
                }
                target.postDelayed(this, SCROLL_TICK_MS)
            }
        }
        follower = task
        target.post(task)
    }

    private fun stopFollowing() {
        follower?.let { label?.removeCallbacks(it) }
        follower = null
    }

    /** Also used by the debug Activity to cancel its pending work on destruction. */
    internal fun stopPreview() = stopFollowing()

    private fun build(): Capsule = Capsule(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PAD_H_DP), dp(PAD_V_DP), dp(PAD_H_DP), dp(PAD_V_DP))
        isClickable = false; isLongClickable = false; isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Opaque matte inside the window: the one window alpha composites BOTH mascot and card.
        background = GradientDrawable().apply {
            setColor(AppTheme.palette.overlayMatte)
            cornerRadius = dp(CORNER_DP).toFloat()
        }.also { cardBackground = it }
        mascot = Mascot(context)
        addView(mascot, LinearLayout.LayoutParams(dp(MASCOT_DP), dp(MASCOT_DP)))
        label = TextView(context).apply {
            setTextColor(AppTheme.palette.overlayInk)
            textSize = TEXT_SP
            setLineSpacing(0f, 1.05f)
            setPadding(dp(10), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // Lines leaving the top fade out instead of being cut.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(FADE_DP))
            isClickable = false; isLongClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextIsSelectable(false)
        }
        hint = TextView(context).apply {
            setTextColor(AppTheme.palette.overlayInk)
            alpha = 0.7f
            textSize = HINT_SP
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
            visibility = View.GONE
            isClickable = false; isLongClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // The column shares the card's fixed height; the hint line only narrows the text viewport.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun params(width: Int, height: Int): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val anchor = metrics.heightPixels * ANCHOR_NUMERATOR / ANCHOR_DENOMINATOR
        // API 30+ uses full-screen layout, so the anchor is measured from the real display edge.
        val frameTop = if (Build.VERSION.SDK_INT >= 30) 0 else statusBarHeight()
        val y = (anchor - height / 2 - frameTop).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            width,
            height,
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
        stopFollowing()
        try {
            manager.removeViewImmediate(owned)
            AppTheme.forget(themeListener)
            view = null
            cardBackground = null
            label = null
            hint = null
            mascot = null
        } catch (_: RuntimeException) {
            // Keep the handle and never add another window when removal is uncertain.
            poisoned = true
        }
    }

    /** Presentation-only hints from the controller; they never gate voice authority. */
    override fun speaking(active: Boolean) { playing = active }
    override fun recognizing(active: Boolean) { }
    override fun finalPreview(active: Boolean) {
        if (active == showingFinal) return
        showingFinal = active
        if (active) startFollowing() else stopFollowing()
    }
    override fun closeHint(text: String?) {
        val target = hint ?: return
        if (text.isNullOrEmpty()) { target.visibility = View.GONE; return }
        if (target.text.toString() != text) target.text = text
        target.visibility = View.VISIBLE
    }
    override fun visualPhase(phase: UiPhase) { view?.visualPhase(phase); mascot?.visualPhase(phase) }

    /** Rotating glow whose colour and speed are specific to each state. */
    private inner class Capsule(context: Context) : LinearLayout(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, 2f * context.resources.displayMetrics.density)
        }
        private val rect = RectF()
        private val rotation = Matrix()
        private var orbit: TimeAnimator? = null
        private var angle = 0f
        private var state = UiPhase.WAKE_WAIT
        private var tint = OverlayGlow.color(state)
        private var shader: SweepGradient? = null
        private var shaderTint = 0
        private var shaderSize = 0L

        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun visualPhase(next: UiPhase) {
            state = next
            val color = OverlayGlow.color(next)
            if (color != tint) { tint = color; invalidate() }
            if (orbit != null) return
            orbit = TimeAnimator().apply {
                setTimeListener { _, _, delta ->
                    angle = (angle + 360f * delta / OverlayGlow.periodMs(state)) % 360f
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (orbit == null) return
            val inset = border.strokeWidth * 1.5f
            rect.set(inset, inset, width - inset, height - inset)
            val size = width.toLong() shl 32 or height.toLong()
            val sweep = shader.takeIf { shaderTint == tint && shaderSize == size }
                ?: SweepGradient(width / 2f, height / 2f,
                    intArrayOf(Color.TRANSPARENT, tint, Color.WHITE, tint, Color.TRANSPARENT), null)
                    .also { shader = it; shaderTint = tint; shaderSize = size }
            rotation.setRotate(angle, width / 2f, height / 2f)
            sweep.setLocalMatrix(rotation)
            border.shader = sweep
            border.setShadowLayer(dp(5).toFloat(), 0f, 0f, tint)
            val corner = dp(CORNER_DP).toFloat()
            canvas.drawRoundRect(rect, corner, corner, border)
        }

        override fun onDetachedFromWindow() {
            orbit?.cancel(); orbit = null; border.clearShadowLayer(); super.onDetachedFromWindow()
        }
    }

    /** Raster mascot driven only by the accepted service phase. The paper background is
     * transparent; headset and outline take the theme's colours and the edge fades softly. */
    private class Mascot(context: Context) : View(context) {
        private val art = MascotArt.atlas(context)
        private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        private val src = Rect()
        private val dst = RectF()
        private var state = UiPhase.WAKE_WAIT
        private var phaseStart = SystemClock.uptimeMillis()
        init {
            require(art.base.width == MascotPlayback.FRAMES * MascotPlayback.CELL &&
                art.base.height == MascotPlayback.ROWS * MascotPlayback.CELL)
        }
        fun visualPhase(next: UiPhase) {
            if (next == state) return
            state = next; phaseStart = SystemClock.uptimeMillis(); invalidate()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            mask.shader = RadialGradient(w / 2f, h / 2f, minOf(w, h) * 0.58f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.74f, 1f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame = MascotPlayback.frame(state, SystemClock.uptimeMillis() - phaseStart)
            val row = MascotPlayback.row(state)
            val cell = MascotPlayback.CELL
            src.set(frame * cell, row * cell, (frame + 1) * cell, (row + 1) * cell)
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            val layer = canvas.saveLayer(dst, null)
            art.draw(canvas, src, dst, AppTheme.palette)
            canvas.drawRect(dst, mask)
            canvas.restoreToCount(layer)
            if (isAttachedToWindow && isShown) postInvalidateDelayed(MascotPlayback.frameMillis(state))
        }
    }
}
