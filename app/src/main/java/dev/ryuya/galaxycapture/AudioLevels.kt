package dev.ryuya.galaxycapture

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
            var i: Int = 0
            val end = min(data.size, valid)
            while (i < end) {
                data[i] = Math.round(data[i] * (level / 100f)).toShort()
                i++
            }
        }
    }

    override fun toString(): String = "AudioLevels[enabled=$enabled, media=$media, mic=$mic]"
}
