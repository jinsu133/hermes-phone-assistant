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
            val views = RemoteViews(context.packageName, R.layout.voice_widget)
            views.setTextViewText(R.id.voice_state, CompanionUiText.title(snapshot) + "\n" +
                context.getString(R.string.widget_recent_notice, snapshot.serviceMessage))
            views.setTextViewText(R.id.voice_lock_state, CompanionUiText.details(snapshot))
            // Existing compact XML may ellipsize; accessibility gets the complete same observation.
            views.setContentDescription(R.id.voice_state, CompanionUiText.title(snapshot) + "\n" +
                snapshot.serviceMessage + "\n" + CompanionUiText.details(snapshot))
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
