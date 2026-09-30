package com.dltechsup.conector

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Toca o áudio do PC (PCM 16 bits) recebido pela rede. Uma thread própria escreve no
 * AudioTrack; se a fila passar de ~0,4 s, descarta os pacotes mais antigos para o som
 * não ficar atrasado em relação ao vídeo.
 */
class PcAudioPlayer {
    private class Chunk(val rate: Int, val channels: Int, val pcm: ByteArray)

    private val queue = LinkedBlockingQueue<Chunk>()
    private var thread: Thread? = null
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackCh = 0

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "pc-audio").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        queue.clear()
        thread?.interrupt()
        thread = null
    }

    fun push(rate: Int, channels: Int, pcm: ByteArray) {
        if (!running) return
        queue.offer(Chunk(rate, channels, pcm))
        val maxBytes = rate * channels * 2 * 2 / 5 // ~0,4 s
        var queued = queue.sumOf { it.pcm.size }
        while (queued > maxBytes) {
            val old = queue.poll() ?: break
            queued -= old.pcm.size
        }
    }

    private fun loop() {
        try {
            while (running) {
                val c = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                ensureTrack(c.rate, c.channels)
                track?.write(c.pcm, 0, c.pcm.size)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            releaseTrack()
        }
    }

    private fun ensureTrack(rate: Int, ch: Int) {
        if (track != null && trackRate == rate && trackCh == ch) return
        releaseTrack()
        val mask = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, rate * ch * 2 / 5) // ~0,2 s de folga contra oscilações da rede
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        trackRate = rate
        trackCh = ch
    }

    private fun releaseTrack() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        track?.release()
        track = null
    }
}
