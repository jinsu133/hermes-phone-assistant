package com.mist.connect

import android.app.PendingIntent
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class VoiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { update(context, ids) }
    override fun onEnabled(context: Context) { CompanionUiRepository.observeWidgets(context, true) }
    override fun onDisabled(context: Context) {
        CompanionUiRepository.observeWidgets(context, false)
        context.getSystemService(AlarmManager::class.java).cancel(expiryIntent(context))
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == EXPIRE) update(context) else super.onReceive(context, intent)
    }
    companion object {
        @Volatile private var lastRendered: String? = null
        @Volatile private var mascot: Pair<MistPalette, android.graphics.Bitmap>? = null

        /** Card, text, buttons and the Nous girl follow the app theme, not the launcher's. */
        private fun applyTheme(context: Context, views: RemoteViews, palette: MistPalette) {
            views.setInt(R.id.widget_root, "setBackgroundResource", palette.widgetCard)
            views.setInt(R.id.voice_on, "setBackgroundResource", palette.widgetPrimary)
            views.setInt(R.id.voice_off, "setBackgroundResource", palette.widgetSecondary)
            views.setInt(R.id.voice_lock_settings, "setBackgroundResource", palette.widgetSecondary)
            views.setInt(R.id.voice_lock_settings, "setColorFilter", palette.ink)
            views.setTextColor(R.id.widget_title, palette.ink)
            views.setTextColor(R.id.voice_state, palette.body)
            views.setTextColor(R.id.voice_lock_state, palette.body)
            views.setTextColor(R.id.voice_on, palette.onPrimary)
            views.setTextColor(R.id.voice_off, palette.ink)
            val cached = mascot
            val bitmap = if (cached != null && cached.first === palette) cached.second else
                MascotArt.portraitBitmap(context, (40 * context.resources.displayMetrics.density).toInt(), palette)
                    .also { mascot = palette to it }
            views.setImageViewBitmap(R.id.widget_mascot, bitmap)
        }
        private const val EXPIRE = "com.mist.connect.UI_OBSERVATION_EXPIRED"
        private fun expiryIntent(context: Context) = PendingIntent.getBroadcast(context, 713,
            Intent(context, VoiceWidget::class.java).setAction(EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun scheduleExpiry(context: Context, elapsed: Long) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetIds(ComponentName(context, VoiceWidget::class.java)).isNotEmpty())
                // Inexact read-only broadcast; no exact-alarm permission, wake, service or audio.
                context.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME, elapsed, expiryIntent(context))
        }
        fun update(context: Context, explicitIds: IntArray? = null) {
            val ids = explicitIds ?: AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, VoiceWidget::class.java))
            CompanionUiRepository.observeWidgets(context, ids.isNotEmpty())
            render(context, CompanionUiRepository.read(context), ids)
        }
        /** Render the delivered immutable value. Must never read/publish back into repository. */
        internal fun render(context: Context, snapshot: CompanionUiState, explicitIds: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = explicitIds ?: manager.getAppWidgetIds(ComponentName(context, VoiceWidget::class.java))
            val title = CompanionUiText.title(context, snapshot)
            val line = HomeStatus.blockerText(snapshot)?.let { context.getString(it) }
                ?: CompanionUiText.nextStep(context, snapshot)
            val ready = context.getString(R.string.widget_ready_line,
                context.getString(if (snapshot.locked) R.string.ready_locked else R.string.ready_unlocked),
                context.getString(R.string.ready_host) + " " + context.getString(when (snapshot.readiness) {
                    SetupReadiness.Phase.AVAILABLE -> R.string.ready_ok
                    SetupReadiness.Phase.UNPAIRED -> R.string.ready_unpaired
                    SetupReadiness.Phase.UNREACHABLE -> R.string.ready_unreachable
                    SetupReadiness.Phase.HOST_UNSUPPORTED -> R.string.ready_unsupported
                    SetupReadiness.Phase.CHECKING -> R.string.ready_checking
                    SetupReadiness.Phase.UNCHECKED -> R.string.ready_unknown
                }))
            // Skip identical redraws: the service refreshes often, but the widget only changes
            // when what it shows changes. An explicit system update always redraws.
            val theme = AppTheme.current
            val key = listOf(theme.key, title, line, ready).joinToString("\n")
            if (explicitIds == null && key == lastRendered) return
            lastRendered = key
            val views = RemoteViews(context.packageName, R.layout.voice_widget)
            views.setTextViewText(R.id.voice_state, title)
            views.setTextViewText(R.id.voice_lock_state, listOf(line, ready).joinToString("\n"))
            views.setContentDescription(R.id.voice_state, listOf(title, line, ready).joinToString("\n"))
            applyTheme(context, views, theme.palette)
            views.setOnClickPendingIntent(R.id.voice_lock_settings, PendingIntent.getActivity(context, 712,
                Intent(context, LockSettingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.voice_on, PendingIntent.getActivity(context, 710,
                Intent(context, VoiceStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.voice_off, PendingIntent.getService(context, 711,
                Intent(context, VoiceRequestService::class.java).setAction(VoiceRequestService.CANCEL_REQUEST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views)
        }
    }
}
