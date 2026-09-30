package com.dltechsup.conector

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Toca o som escolhido para avisar que a notificação veio do PC.
 * Modo "alto": usa o stream de ALARME (ignora o modo silencioso do toque)
 * e sobe o volume do alarme temporariamente.
 */
object SoundPlayer {
    private const val MAX_MS = 8000L
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedAlarmVolume = -1
    private var am: AudioManager? = null
    private val stopRunnable = Runnable { stop() }

    @Synchronized
    fun play(ctx: Context, prefs: Prefs) {
        stop()
        val app = ctx.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am = audio
        val uri: Uri = prefs.soundUri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            val loud = prefs.loud
            if (loud) {
                savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, (max * prefs.volume / 100).coerceAtLeast(1), 0)
            }
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(app, uri)
            if (!loud) mp.setVolume(prefs.volume / 100f, prefs.volume / 100f)
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.prepare()
            mp.start()
            player = mp
            main.postDelayed(stopRunnable, MAX_MS)
        } catch (e: Exception) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(stopRunnable)
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        if (savedAlarmVolume >= 0) {
            try {
                am?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            } catch (_: Exception) {
            }
            savedAlarmVolume = -1
        }
    }
}
