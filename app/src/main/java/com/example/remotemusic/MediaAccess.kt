package com.example.remotemusic

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

object MediaAccess {
    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    fun isEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(ctx.packageName)
    }

    private fun controller(): MediaController? {
        val ctx = appCtx ?: return null
        if (!isEnabled(ctx)) return null
        return try {
            val comp = ComponentName(ctx, NotifAccess::class.java)
            val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = mgr.getActiveSessions(comp)
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        } catch (e: SecurityException) { null }
    }

    fun play() = controller()?.transportControls?.play()
    fun pause() = controller()?.transportControls?.pause()
    fun playPause() {
        val c = controller() ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }
    fun next() = controller()?.transportControls?.skipToNext()
    fun prev() = controller()?.transportControls?.skipToPrevious()
    fun volUp() = adjustVolume(AudioManager.ADJUST_RAISE)
    fun volDown() = adjustVolume(AudioManager.ADJUST_LOWER)
    private fun adjustVolume(dir: Int) {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    fun nowPlaying(): String {
        val c = controller() ?: return "Ничего не играет"
        val md = c.metadata ?: return "Ничего не играет"
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "?"
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        return if (artist.isNotEmpty()) "$title — $artist" else title
    }
}

class NotifAccess : NotificationListenerService()
