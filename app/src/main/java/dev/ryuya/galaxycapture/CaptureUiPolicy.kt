package dev.ryuya.galaxycapture

import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* A window keeps its capture policy across native relayouts for the current recording. */
@JvmRecord
data class CaptureUiPolicy(val exclude: Boolean, val hidden: Boolean) {
    fun privateFlags(original: Int): Int {
        return if (exclude) original or CaptureUiPolicy.EXCLUDE_PRIVATE_FLAG else original
    }

    fun flags(original: Int): Int {
        return if (hidden) original or CaptureUiPolicy.NO_INPUT_FLAGS else original
    }

    fun alpha(original: Float): Float {
        return if (hidden) 0f else original
    }

    companion object {
        // WindowStateAnimator adds SKIP_SCREENSHOT when this private flag is present at creation.
        const val EXCLUDE_PRIVATE_FLAG: Int = 0x00100000
        const val NO_INPUT_FLAGS: Int = 0x18 // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE
        @JvmField val NATIVE: CaptureUiPolicy = CaptureUiPolicy(false, false)

        @JvmStatic
        fun from(options: Options, recording: Boolean, allowed: Boolean): CaptureUiPolicy? {
            if (!allowed || !options.enabled) return CaptureUiPolicy.NATIVE
            return CaptureUiPolicy(
                options.excludeCaptureControls,
                recording && options.hideRecordingControls,
            )
        }
    }

    override fun toString(): String = "CaptureUiPolicy[exclude=$exclude, hidden=$hidden]"
}
