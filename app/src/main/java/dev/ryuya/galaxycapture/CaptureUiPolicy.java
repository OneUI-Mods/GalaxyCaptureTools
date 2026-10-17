package dev.ryuya.galaxycapture;

/** A window keeps its capture policy across native relayouts for the current recording. */
record CaptureUiPolicy(boolean exclude, boolean hidden) {
  // WindowStateAnimator adds SKIP_SCREENSHOT when this private flag is present at creation.
  static final int EXCLUDE_PRIVATE_FLAG = 0x00100000;
  static final int NO_INPUT_FLAGS = 0x18; // FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE
  static final CaptureUiPolicy NATIVE = new CaptureUiPolicy(false, false);

  static CaptureUiPolicy from(Options options, boolean recording, boolean allowed) {
    if (!allowed || !options.enabled()) return NATIVE;
    return new CaptureUiPolicy(
        options.excludeCaptureControls(), recording && options.hideRecordingControls());
  }

  int privateFlags(int original) {
    return exclude ? original | EXCLUDE_PRIVATE_FLAG : original;
  }

  int flags(int original) {
    return hidden ? original | NO_INPUT_FLAGS : original;
  }

  float alpha(float original) {
    return hidden ? 0f : original;
  }
}
