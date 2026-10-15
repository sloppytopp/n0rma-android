package io.github.sloppytopp.homewatch.scan

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.sloppytopp.homewatch.detect.RfSweep
import kotlin.math.PI
import kotlin.math.sin

/** Plays the tone-test pattern (1 kHz on/off windows) through the phone speaker at full media volume, restoring the volume afterwards. */
class ToneOut(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val oldVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    private var track: AudioTrack? = null

    /** Returns immediately; audio plays in the background. */
    fun start(pattern: IntArray, winMs: Int) {
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }
        val rate = 44100
        val per = rate * winMs / 1000
        val pcm = ShortArray(pattern.size * per) { i -> if (pattern[i / per] == 1) (0.9 * 32767 * sin(2 * PI * RfSweep.TONE_HZ * i / rate)).toInt().toShort() else 0 }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size)
        t.setVolume(1f)
        t.play()
        track = t
    }

    fun finish() {
        runCatching { track?.stop(); track?.release() }
        track = null
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, oldVol, 0) }
    }
}
