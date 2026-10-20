package dev.ryuya.galaxycapture;

/** Android 17 is reviewed independently; never fall back to Android 16 on a mismatch. */
final class Android17Hooks {
  static final String DIALOG_BUILDER = "androidx.recyclerview.widget.g2";
  static final String DIALOG_CREATE = "h";
  static final String BUILD = "CP2A.260605.016.S948QOPU1BZI9";
  static final long RECORDER_VERSION = 624429000L;
  static final String CANDIDATE_FIRMWARE = "S948QOPU1BZI9";

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    // BZI9 contracts were reviewed against ROM and matching installed APKs.
    // Each feature still resolves its full runtime contract before changing behavior.
    return sdk == 37
        && "samsung".equalsIgnoreCase(manufacturer)
        && "SM-S948Q".equals(model)
        && "m3q".equals(device)
        && BUILD.equals(display);
  }
}
