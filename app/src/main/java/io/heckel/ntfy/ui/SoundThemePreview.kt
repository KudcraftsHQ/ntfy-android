package io.heckel.ntfy.ui

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import io.heckel.ntfy.msg.CatalogSound
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.util.Log

/**
 * kudcrafts: sound themes. Plays the seven clips of one theme back to back, for the picker in the settings.
 * Uses the notification stream, so it sounds exactly as loud as a real notification would (and is silent
 * when the phone is). Main thread only.
 */
class SoundThemePreview(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var run = 0 // Bumped on every play/stop, so callbacks of an earlier run do nothing

    /** [onClip] is called with the sound class when its clip starts, and with null when the theme is through */
    fun play(theme: String, onClip: (String?) -> Unit) {
        stop()
        playFrom(theme, 0, run, onClip)
    }

    fun stop() {
        run++
        handler.removeCallbacksAndMessages(null)
        release()
    }

    private fun playFrom(theme: String, index: Int, token: Int, onClip: (String?) -> Unit) {
        if (token != run) return
        val soundClass = CatalogSound.AUDIBLE.getOrNull(index)
        if (soundClass == null) {
            onClip(null)
            return
        }
        val next = { handler.postDelayed({ playFrom(theme, index + 1, token, onClip) }, GAP_MILLIS) }
        val uri = NotificationService.catalogClipUri(context, theme, soundClass)
        if (uri == null) {
            next()
            return
        }
        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setAudioAttributes(NotificationService.catalogAudioAttributes())
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.setOnCompletionListener {
                if (token == run) {
                    release()
                    next()
                }
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Cannot play $uri ($what, $extra)")
                if (token == run) {
                    release()
                    next()
                }
                true
            }
            mediaPlayer.prepare() // A bundled clip of a few KB: no need for prepareAsync
            mediaPlayer.start()
            onClip(soundClass)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play $uri: ${e.message}", e)
            release()
            next()
        }
    }

    private fun release() {
        try {
            player?.release()
        } catch (_: Exception) {
            // Already released
        }
        player = null
    }

    companion object {
        private const val TAG = "NtfySoundThemePreview"
        private const val GAP_MILLIS = 350L
    }
}
