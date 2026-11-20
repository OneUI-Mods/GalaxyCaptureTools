package dev.ryuya.galaxycapture

import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* One catalog for module UI, Samsung settings, and the provider write allowlist. */
object CaptureSettings {
    @JvmField
    val MASTER: CaptureSettings.Toggle =
        CaptureSettings.Toggle("enabled", R.string.enabled, R.string.master_scope_detail, false)
    @JvmField
    val COMMON: Array<CaptureSettings.Toggle?>? =
        arrayOf<CaptureSettings.Toggle?>(
            CaptureSettings.Toggle("rotation", R.string.rotation, R.string.rotation_detail, true),
            CaptureSettings.Toggle(
                "ignore_half_turns",
                R.string.ignore_half_turns,
                R.string.ignore_half_turns_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "single_window",
                R.string.single_window,
                R.string.single_window_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "secure_recording",
                R.string.secure_recording,
                R.string.secure_recording_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "app_audio_only",
                R.string.app_audio_only,
                R.string.app_audio_only_detail,
                true,
            ),
            CaptureSettings.Toggle(
                "include_call_audio",
                R.string.include_call_audio,
                R.string.include_call_audio_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "hide_duplicate_indicator",
                R.string.hide_duplicate_indicator,
                R.string.hide_duplicate_indicator_detail,
                true,
            ),
        )
    @JvmField
    val GALAXY: Array<CaptureSettings.Toggle?>? =
        arrayOf<CaptureSettings.Toggle?>(
            CaptureSettings.Toggle(
                "recording_icon_right",
                R.string.recording_icon_right,
                R.string.recording_icon_right_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "samsung_app",
                R.string.samsung_app,
                R.string.samsung_app_detail,
                true,
            ),
            CaptureSettings.Toggle(
                "countdown",
                R.string.countdown,
                R.string.countdown_detail,
                true,
            ),
            CaptureSettings.Toggle(
                "speaker_media",
                R.string.speaker_media,
                R.string.speaker_media_detail,
                true,
            ),
            CaptureSettings.Toggle(
                "recording_volume",
                R.string.recording_volume,
                R.string.recording_volume_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "secure_screenshot",
                R.string.secure_screenshot,
                R.string.secure_screenshot_detail,
                false,
            ),
            CaptureSettings.Toggle(
                "exclude_capture_controls",
                R.string.exclude_capture_controls,
                R.string.exclude_capture_controls_detail,
                true,
            ),
            CaptureSettings.Toggle(
                "hide_recording_controls",
                R.string.hide_recording_controls,
                R.string.hide_recording_controls_detail,
                false,
            ),
        )

    @JvmStatic
    fun all(): Array<CaptureSettings.Toggle?> {
        val all =
            arrayOfNulls<CaptureSettings.Toggle>(
                1 + CaptureSettings.COMMON!!.size + CaptureSettings.GALAXY!!.size
            )
        all[0] = CaptureSettings.MASTER
        System.arraycopy(CaptureSettings.COMMON, 0, all, 1, CaptureSettings.COMMON!!.size)
        System.arraycopy(
            CaptureSettings.GALAXY,
            0,
            all,
            1 + CaptureSettings.COMMON!!.size,
            CaptureSettings.GALAXY!!.size,
        )
        return all
    }

    @JvmRecord
    data class Toggle(
        val key: String?,
        val title: Int,
        val detail: Int,
        val defaultValue: Boolean,
    ) {
        override fun toString(): String =
            "Toggle[key=$key, title=$title, detail=$detail, defaultValue=$defaultValue]"
    }
}
