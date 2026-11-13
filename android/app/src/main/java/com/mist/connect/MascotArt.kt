package com.mist.connect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF

/** Nous girl drawn from three layers (see android/tools/mascot_layers.py): the transparent
 * drawing, the headset accents tinted with the theme, and an outline in the theme's colour. */
internal class MascotLayers(val base: Bitmap, val accent: Bitmap, val outline: Bitmap) {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var tinted: MistPalette? = null

    private fun tint(palette: MistPalette) {
        if (tinted === palette) return
        tinted = palette
        accentPaint.colorFilter = LightingColorFilter(palette.headset and 0xFFFFFF, 0)
        outlinePaint.colorFilter = PorterDuffColorFilter(palette.mascotOutline, PorterDuff.Mode.SRC_IN)
    }

    /** src null draws the whole layer. */
    fun draw(canvas: Canvas, src: Rect?, dst: RectF, palette: MistPalette) {
        tint(palette)
        canvas.drawBitmap(outline, src, dst, outlinePaint)
        canvas.drawBitmap(base, src, dst, basePaint)
        canvas.drawBitmap(accent, src, dst, accentPaint)
    }
}

internal object MascotArt {
    @Volatile private var portrait: MascotLayers? = null
    @Volatile private var atlas: MascotLayers? = null

    private fun load(context: Context, base: Int, accent: Int, outline: Int): MascotLayers {
        val res = context.resources
        val opts = BitmapFactory.Options().apply { inScaled = false }
        return MascotLayers(checkNotNull(BitmapFactory.decodeResource(res, base, opts)),
            checkNotNull(BitmapFactory.decodeResource(res, accent, opts)),
            checkNotNull(BitmapFactory.decodeResource(res, outline, opts)))
    }

    fun portrait(context: Context): MascotLayers = portrait ?: synchronized(this) {
        portrait ?: load(context, R.drawable.nous_girl_portrait_base, R.drawable.nous_girl_portrait_accent,
            R.drawable.nous_girl_portrait_outline).also { portrait = it }
    }

    fun atlas(context: Context): MascotLayers = atlas ?: synchronized(this) {
        atlas ?: load(context, R.drawable.overlay_state_atlas_base, R.drawable.overlay_state_atlas_accent,
            R.drawable.overlay_state_atlas_outline).also { atlas = it }
    }

    /** Square portrait rendered for one theme, for Compose images and widget RemoteViews. */
    fun portraitBitmap(context: Context, sizePx: Int, palette: MistPalette): Bitmap {
        val size = sizePx.coerceIn(16, 512)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        portrait(context).draw(Canvas(out), null, RectF(0f, 0f, size.toFloat(), size.toFloat()), palette)
        return out
    }
}
