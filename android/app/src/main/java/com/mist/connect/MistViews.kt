package com.mist.connect

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The chosen theme's palette for the few screens built from plain Android views. */
internal object MistViews {
    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Paints the window and returns a scrolling column that fills the screen. */
    fun screen(activity: Activity): LinearLayout = with(activity) {
        window.setBackgroundDrawable(ColorDrawable(AppTheme.palette.background))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(AppTheme.palette.background)
            isFillViewport = true
            fitsSystemWindows = true
            addView(column)
        })
        column
    }

    fun title(activity: Activity, text: CharSequence = ""): TextView = TextView(activity).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(AppTheme.palette.ink)
        setPadding(0, 0, 0, activity.dp(16))
    }

    /** Rounded card holding body text, matching the app's dialogs. */
    fun card(activity: Activity, text: CharSequence = ""): TextView = TextView(activity).apply {
        this.text = text
        textSize = 16f
        setLineSpacing(0f, 1.25f)
        setTextColor(AppTheme.palette.body)
        val pad = activity.dp(20)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = activity.dp(24).toFloat()
            setColor(AppTheme.palette.surface)
            setStroke(activity.dp(1), AppTheme.palette.line)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = activity.dp(20) }
    }

    /** Filled pill for the main action, outlined pill otherwise. */
    fun button(activity: Activity, text: CharSequence, primary: Boolean, onClick: ((View) -> Unit)? = null): Button =
        Button(activity, null, 0, android.R.style.Widget_Material_Button_Borderless).apply {
            this.text = text
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = activity.dp(56)
            stateListAnimator = null
            val palette = AppTheme.palette
            val ink = if (primary) palette.onPrimary else palette.action
            setTextColor(ink)
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(28).toFloat()
                if (primary) setColor(palette.primary)
                else { setColor(0); setStroke(activity.dp(1), palette.line) }
            }
            backgroundTintList = null
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(ink and 0x33FFFFFF), null,
                GradientDrawable().apply { cornerRadius = activity.dp(28).toFloat(); setColor(-1) })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = activity.dp(12) }
            if (onClick != null) setOnClickListener(onClick)
        }
}
