package dev.ryuya.galaxycapture

import android.os.Bundle
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

@JvmRecord
data class Options(
    val enabled: Boolean,
    val rotation: Boolean,
    val samsungApp: Boolean,
    val singleWindow: Boolean,
    val appAudioOnly: Boolean,
    val ignoreHalfTurns: Boolean,
    val hideDuplicateIndicator: Boolean,
    val speakerMedia: Boolean,
    val excludeCaptureControls: Boolean,
    val hideRecordingControls: Boolean,
    val countdown: Boolean,
    val secureScreenshot: Boolean,
    val secureRecording: Boolean,
    val recordingVolume: Boolean,
    val mediaVolume: Int,
    val micVolume: Int,
    val recordingIconRight: Boolean,
    val includeCallAudio: Boolean,
) {

    companion object {
        @JvmField
        val OFF: Options =
            Options(
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                100,
                100,
                false,
                false,
            )

        @JvmStatic
        fun from(b: Bundle?): Options? {
            return if (b == null) Options.OFF
            else
                Options(
                    b.getBoolean("enabled", false),
                    b.getBoolean("rotation", true),
                    b.getBoolean("samsung_app", true),
                    b.getBoolean("single_window", false),
                    b.getBoolean("app_audio_only", true),
                    b.getBoolean("ignore_half_turns", false),
                    b.getBoolean("hide_duplicate_indicator", true),
                    b.getBoolean("speaker_media", true),
                    b.getBoolean("exclude_capture_controls", true),
                    b.getBoolean("hide_recording_controls", false),
                    b.getBoolean("countdown", true),
                    b.getBoolean("secure_screenshot", false),
                    b.getBoolean("secure_recording", false),
                    b.getBoolean("recording_volume", false),
                    AudioLevels.clamp(b.getInt("media_volume", 100)),
                    AudioLevels.clamp(b.getInt("mic_volume", 100)),
                    b.getBoolean("recording_icon_right", false),
                    b.getBoolean("include_call_audio", false),
                )
        }
    }

    override fun toString(): String =
        "Options[enabled=$enabled, rotation=$rotation, samsungApp=$samsungApp, singleWindow=$singleWindow, appAudioOnly=$appAudioOnly, ignoreHalfTurns=$ignoreHalfTurns, hideDuplicateIndicator=$hideDuplicateIndicator, speakerMedia=$speakerMedia, excludeCaptureControls=$excludeCaptureControls, hideRecordingControls=$hideRecordingControls, countdown=$countdown, secureScreenshot=$secureScreenshot, secureRecording=$secureRecording, recordingVolume=$recordingVolume, mediaVolume=$mediaVolume, micVolume=$micVolume, recordingIconRight=$recordingIconRight, includeCallAudio=$includeCallAudio]"
}
