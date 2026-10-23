package com.thanhtaxixanh.cardriverlauncher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService

// Requires the user to explicitly enable Notification Access. No automatic permission grant.
class MediaAccessService : NotificationListenerService() {
    override fun onListenerConnected() { MediaSnapshot.refresh(this) }
    override fun onListenerDisconnected() { MediaSnapshot.controller = null }
}

object MediaSnapshot {
    @Volatile var controller: MediaController? = null
    fun refresh(context: Context) {
        try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            controller = manager.getActiveSessions(ComponentName(context, MediaAccessService::class.java))
                .firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        } catch (_: SecurityException) { controller = null }
        catch (_: Exception) { controller = null }
    }
    fun title() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Chưa phát nhạc"
    fun artist() = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Cho phép truy cập thông báo"
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun toggle() {
        val c = controller ?: return
        val playing = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }
}
