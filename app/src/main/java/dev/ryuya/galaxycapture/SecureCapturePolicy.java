package dev.ryuya.galaxycapture;

/** Capturing permission is specific to one operation, never a source-window flag change. */
final class SecureCapturePolicy {
  static boolean recording(boolean enabled, boolean option, boolean guard, String owner, String name) {
    return enabled && option && guard
        && (("com.android.systemui".equals(owner) && "Recording Display".equals(name))
            || ("com.samsung.android.app.smartcapture".equals(owner) && "ScreenRecorder".equals(name)));
  }
  static boolean screenshot(boolean enabled, boolean option, boolean guard, String owner) {
    return enabled && option && guard && "com.android.systemui".equals(owner);
  }
  static boolean onlySecureFlag(int reasons, boolean sensitive, boolean devicePolicy) {
    return reasons == 16 && !sensitive && !devicePolicy;
  }
}
