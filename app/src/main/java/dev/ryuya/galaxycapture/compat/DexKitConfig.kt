package dev.ryuya.galaxycapture.compat

internal object DexKitConfig {
    const val SCHEMA = 1
    const val MODULE_ID = "dev.ryuya.galaxycapture"
    val MODULE_VERSION
        get() = dev.ryuya.galaxycapture.BuildConfig.VERSION_CODE

    const val PROFILE_HASH = "16bf56e3065758877698cbe078a09e9bec54a80b5a1a513b5adec43cef484199"
    val TARGETS = setOf("com.android.systemui", "com.samsung.android.app.smartcapture")
    val HOSTS = setOf("com.android.systemui", "com.samsung.android.app.smartcapture")
    val CLASSES_BY_TARGET =
        mapOf(
            "com.android.systemui" to
                listOf(
                    "androidx.preference.Preference",
                    "androidx.preference.Preference\$OnPreferenceChangeListener",
                    "androidx.preference.Preference\$OnPreferenceClickListener",
                    "com.android.systemui.mediaprojection.MediaProjectionCaptureTarget",
                    "com.android.systemui.screenrecord.ScreenInternalAudioRecorder",
                    "com.android.systemui.screenrecord.ScreenMediaRecorder",
                    "com.android.systemui.screenrecord.ScreenRecordPermissionContentManager",
                    "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                    "com.android.systemui.statusbar.phone.SamsungCastIconController",
                    "com.android.systemui.statusbar.phone.SamsungCastIconController\$removeRunnable\$1",
                    "com.android.systemui.statusbar.phone.TouchInterceptFrameLayout",
                    "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                    "com.android.systemui.statusbar.phone.ongoingcast.SamsungOngoingCastController",
                    "com.android.systemui.statusbar.phone.ui.StatusBarIconController",
                    "com.android.systemui.statusbar.policy.CastController",
                ),
            "com.samsung.android.app.smartcapture" to
                listOf(
                    "F3.c",
                    "M4.c",
                    "androidx.appcompat.widget.SeslSwitchBar\$OnSwitchChangeListener",
                    "androidx.preference.Preference",
                    "androidx.preference.Preference\$OnPreferenceChangeListener",
                    "androidx.preference.Preference\$OnPreferenceClickListener",
                    "androidx.recyclerview.widget.g2",
                    "b6.a",
                    "b6.c",
                    "com.samsung.android.app.settings.FolderPickerPreference",
                    "com.samsung.android.app.settings.SettingsActivity",
                    "com.samsung.android.app.settings.SettingsFragment",
                    "com.samsung.android.app.smartcapture.baseutil.reflections.MultiWindowManagerReflection",
                    "com.samsung.android.app.smartcapture.baseutil.sep.SepWrapper\$MediaProjectionManager",
                    "com.samsung.android.app.smartcapture.baseutil.view.CaptureUtils",
                    "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController",
                    "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController\$RecordingStopReason",
                    "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderService",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.ScreenRecorder",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.ScreenRecorderContext",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.ScreenRecorderListener",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.AudioBuffer",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.BaseAudioRecorder",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.EnhancedMicAudioRecorder",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder",
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.ScreenRecorderCircularBuffer",
                    "com.samsung.android.app.smartcapture.screenrecorder.util.AudioUtil",
                    "com.samsung.android.app.smartcapture.screenrecorder.view.EnhancedRadioButton",
                    "j3.i",
                    "j3.j",
                    "l3.B",
                    "m8.c",
                    "q7.i",
                    "q7.j",
                    "s7.d0",
                    "z.p",
                ),
        )
    val KNOWN_VERSIONS =
        mapOf(
            "com.android.systemui" to setOf(36L, 37L),
            "com.samsung.android.app.smartcapture" to setOf(617609000L, 624429000L),
        )
    val PROFILE_PACKAGES =
        mapOf(
            36 to setOf("com.android.systemui", "com.samsung.android.app.smartcapture"),
            37 to setOf("com.android.systemui", "com.samsung.android.app.smartcapture"),
        )
}
