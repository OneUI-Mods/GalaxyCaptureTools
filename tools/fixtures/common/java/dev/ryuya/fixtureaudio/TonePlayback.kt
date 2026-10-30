package dev.ryuya.fixtureaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin

/* Internal APKs only: a steady tone whose usage and UID can be measured independently. */
class TonePlayback(usage: Int, frequency: Int) : AutoCloseable {
    private var track: AudioTrack?

    init {
        val samples = ShortArray(48000)
        for (frame in samples.indices) samples[frame] =
            (1400 * sin(2 * Math.PI * frequency * frame / 48000)).toInt().toShort()
        track =
            android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(
                            if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                AudioAttributes.CONTENT_TYPE_SPEECH
                            else AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        try {
            check(
                !(track!!.write(samples, 0, samples.size) != samples.size ||
                    track!!.setLoopPoints(0, samples.size, -1) != AudioTrack.SUCCESS)
            ) {
                "Fixture tone was not initialized"
            }
            track!!.play()
        } catch (failure: RuntimeException) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (track == null) return
        try {
            track!!.stop()
        } finally {
            track!!.release()
            track = null
        }
    }
}
