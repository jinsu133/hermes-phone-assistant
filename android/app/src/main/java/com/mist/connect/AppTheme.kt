package com.mist.connect

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CopyOnWriteArraySet

/** Colours every surface takes from the chosen theme: app, sub-screens, overlay, widget and the
 * Nous girl's headset and outline. Add a theme by adding one entry to [AppThemeId]. */
internal data class MistPalette(
    val background: Int, val surface: Int, val line: Int,
    val ink: Int, val body: Int,
    val primary: Int, val onPrimary: Int, val action: Int,
    /** Multiplied into the headset accent layer; the authored gold is the Basic value. */
    val headset: Int,
    /** Silhouette outline, alpha included. */
    val mascotOutline: Int,
    val overlayMatte: Int, val overlayInk: Int,
    val widgetCard: Int, val widgetPrimary: Int, val widgetSecondary: Int,
)

internal enum class AppThemeId(val key: String, val label: Int, val night: Boolean, val palette: MistPalette) {
    BASIC("basic", R.string.theme_basic, false, MistPalette(
        background = 0xFFF8EFE3.toInt(), surface = 0xFFFFF8EE.toInt(), line = 0xFFD4C7B8.toInt(),
        ink = 0xFF26221F.toInt(), body = 0xFF554C43.toInt(),
        primary = 0xFF262321.toInt(), onPrimary = 0xFFFFF7EB.toInt(), action = 0xFF6D5030.toInt(),
        headset = 0xFFD2A464.toInt(), mascotOutline = 0xCCC4A062.toInt(),
        overlayMatte = 0xFFFDECD9.toInt(), overlayInk = 0xFF26221F.toInt(),
        widgetCard = R.drawable.widget_card, widgetPrimary = R.drawable.widget_button_primary,
        widgetSecondary = R.drawable.widget_button_secondary)),
    DARK("dark", R.string.theme_dark, true, MistPalette(
        background = 0xFF211F1E.toInt(), surface = 0xFF292624.toInt(), line = 0xFF5B5249.toInt(),
        ink = 0xFFF2E6D8.toInt(), body = 0xFFD5C4B2.toInt(),
        primary = 0xFFE8C895.toInt(), onPrimary = 0xFF2C241A.toInt(), action = 0xFFE8C895.toInt(),
        headset = 0xFFF0CE96.toInt(), mascotOutline = 0xE6E8C895.toInt(),
        overlayMatte = 0xFF292624.toInt(), overlayInk = 0xFFF2E6D8.toInt(),
        widgetCard = R.drawable.widget_card_dark, widgetPrimary = R.drawable.widget_button_primary_dark,
        widgetSecondary = R.drawable.widget_button_secondary_dark));

    companion object {
        val DEFAULT = BASIC
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

internal object AppTheme {
    private const val PREFS = "appearance"
    private const val KEY = "theme"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Compose reads this, so a change recomposes every open screen. */
    val state = mutableStateOf(AppThemeId.DEFAULT)
    val current: AppThemeId get() = state.value
    val palette: MistPalette get() = state.value.palette

    fun init(context: Context) {
        state.value = AppThemeId.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        applyNightMode(context)
    }

    fun set(context: Context, id: AppThemeId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id.key).apply()
        state.value = id
        applyNightMode(context)
        VoiceWidget.update(context.applicationContext)
        main.post { listeners.forEach { it() } }
    }

    /** Live surfaces outside Compose (the overlay) redraw through this. */
    fun observe(listener: () -> Unit) { listeners += listener }
    fun forget(listener: () -> Unit) { listeners -= listener }

    /** System dialogs and -night resources follow the app's theme, not the phone's. */
    private fun applyNightMode(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val mode = if (current.night) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }
}
