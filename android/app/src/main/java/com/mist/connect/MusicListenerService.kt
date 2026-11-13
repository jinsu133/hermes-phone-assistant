package com.mist.connect

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MusicListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        fun getNowPlaying(context: Context): String {
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val componentName = ComponentName(context, MusicListenerService::class.java)
                val controllers = msm.getActiveSessions(componentName)

                if (controllers.isEmpty()) return "No music playing"

                val controller = controllers[0]
                val metadata = controller.metadata ?: return "No music playing"
                val state = controller.playbackState

                val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                val playing = if (state?.state == android.media.session.PlaybackState.STATE_PLAYING) "playing" else "paused"

                val info = StringBuilder()
                info.append("$title - $artist")
                if (album.isNotEmpty()) info.append(" ($album)")
                info.append(" [$playing]")

                val pkg = controller.packageName
                val appName = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg
                }

                info.append(" - $appName")
                info.toString()
            } catch (e: Exception) {
                "Failed to read playback info: ${e.message}"
            }
        }
    }
}
