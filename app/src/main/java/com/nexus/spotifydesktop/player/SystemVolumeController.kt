package com.nexus.spotifydesktop.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * Controls Android media volume (STREAM_MUSIC) from an in-app slider.
 * Spotify on-phone audio uses this stream, so the slider actually changes what you hear
 * without calling Spotify's volume API (which 403s on smartphones).
 */
class SystemVolumeController(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun getNormalized(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setNormalized(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        // No FLAG_SHOW_UI — keep control inside our slider, not the system toast
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun registerVolumeKeys(onChanged: (Float) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == VOLUME_CHANGED_ACTION ||
                    intent?.action == "android.media.VOLUME_CHANGED_ACTION"
                ) {
                    onChanged(getNormalized())
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        return { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
