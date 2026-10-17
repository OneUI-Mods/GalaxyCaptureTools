package dev.ryuya.galaxycapture;

/** One catalog for module UI, Samsung settings, and the provider write allowlist. */
final class CaptureSettings {
  record Toggle(String key, int title, int detail, boolean defaultValue) {}
  static final Toggle MASTER = new Toggle("enabled", R.string.enabled, R.string.master_scope_detail, false);
  static final Toggle[] COMMON = {
    new Toggle("rotation", R.string.rotation, R.string.rotation_detail, true),
    new Toggle("ignore_half_turns", R.string.ignore_half_turns, R.string.ignore_half_turns_detail, false),
    new Toggle("single_window", R.string.single_window, R.string.single_window_detail, false),
    new Toggle("secure_recording", R.string.secure_recording, R.string.secure_recording_detail, false),
    new Toggle("app_audio_only", R.string.app_audio_only, R.string.app_audio_only_detail, true),
    new Toggle("hide_duplicate_indicator", R.string.hide_duplicate_indicator, R.string.hide_duplicate_indicator_detail, true)
  };
  static final Toggle[] GALAXY = {
    new Toggle("recording_icon_right", R.string.recording_icon_right, R.string.recording_icon_right_detail, false),
    new Toggle("samsung_app", R.string.samsung_app, R.string.samsung_app_detail, true),
    new Toggle("countdown", R.string.countdown, R.string.countdown_detail, true),
    new Toggle("speaker_media", R.string.speaker_media, R.string.speaker_media_detail, true),
    new Toggle("recording_volume", R.string.recording_volume, R.string.recording_volume_detail, false),
    new Toggle("secure_screenshot", R.string.secure_screenshot, R.string.secure_screenshot_detail, false),
    new Toggle("exclude_capture_controls", R.string.exclude_capture_controls, R.string.exclude_capture_controls_detail, true),
    new Toggle("hide_recording_controls", R.string.hide_recording_controls, R.string.hide_recording_controls_detail, false)
  };
  static Toggle[] all() {
    Toggle[] all = new Toggle[1 + COMMON.length + GALAXY.length];
    all[0] = MASTER;
    System.arraycopy(COMMON, 0, all, 1, COMMON.length);
    System.arraycopy(GALAXY, 0, all, 1 + COMMON.length, GALAXY.length);
    return all;
  }
}
