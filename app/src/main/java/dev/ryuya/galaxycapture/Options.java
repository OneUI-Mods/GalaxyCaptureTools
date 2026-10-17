package dev.ryuya.galaxycapture;

import android.os.Bundle;

record Options(boolean enabled, boolean rotation, boolean samsungApp, boolean singleWindow,
    boolean appAudioOnly, boolean ignoreHalfTurns, boolean hideDuplicateIndicator,
    boolean speakerMedia, boolean excludeCaptureControls, boolean hideRecordingControls,
    boolean countdown, boolean secureScreenshot, boolean secureRecording,
    boolean recordingVolume, int mediaVolume, int micVolume) {
  static final Options OFF =
      new Options(false, false, false, false, true, false, false, false, false, false, true, false, false, false, 100, 100);

  static Options from(Bundle b) {
    return b == null
        ? OFF
        : new Options(b.getBoolean("enabled", false), b.getBoolean("rotation", true),
              b.getBoolean("samsung_app", true), b.getBoolean("single_window", false),
              b.getBoolean("app_audio_only", true), b.getBoolean("ignore_half_turns", false),
              b.getBoolean("hide_duplicate_indicator", true),
              b.getBoolean("speaker_media", true),
              b.getBoolean("exclude_capture_controls", true),
              b.getBoolean("hide_recording_controls", false), b.getBoolean("countdown", true),
              b.getBoolean("secure_screenshot", false), b.getBoolean("secure_recording", false),
              b.getBoolean("recording_volume", false), AudioLevels.clamp(b.getInt("media_volume", 100)),
              AudioLevels.clamp(b.getInt("mic_volume", 100)));
  }
}
