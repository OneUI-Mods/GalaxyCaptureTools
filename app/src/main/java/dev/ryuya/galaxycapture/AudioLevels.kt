package dev.ryuya.galaxycapture

import java.util.Arrays
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Attenuate native PCM inputs in place; no buffering, resampling, or gain above unity. */
@JvmRecord
data class AudioLevels(val enabled: Boolean, val media: Int, val mic: Int) {

    companion object {
        @JvmStatic
        fun clamp(value: Int): Int {
            return max(0, min(100, value))
        }

        @JvmStatic
        fun from(o: Options): AudioLevels? {
            return AudioLevels(
                o.enabled && o.recordingVolume,
                AudioLevels.clamp(o.mediaVolume),
                AudioLevels.clamp(o.micVolume),
            )
        }

        @JvmStatic
        fun scale(data: ShortArray, valid: Int, percent: Int) {
            val level = AudioLevels.clamp(percent)
            if (level == 100 || valid <= 0) return
            val end = min(data.size, valid)
            if (level == 0) {
                Arrays.fill(data, 0, end, 0.toShort())
                return
            }
            val gain = level / 100f
            for (index in 0 until end) data[index] = Math.round(data[index] * gain).toShort()
        }
    }

    override fun toString(): String = "AudioLevels[enabled=$enabled, media=$media, mic=$mic]"
}
