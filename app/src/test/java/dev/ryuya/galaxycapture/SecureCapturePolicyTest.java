package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;
import org.junit.Test;

public class SecureCapturePolicyTest {
  @Test
  public void separateSwitchesCannotEnableEachOther() {
    assertTrue(SecureCapturePolicy.screenshot(true, true, true, "com.android.systemui"));
    assertFalse(
        SecureCapturePolicy.recording(
            true, false, true, "com.android.systemui", "Recording Display"));
    assertFalse(SecureCapturePolicy.screenshot(true, false, true, "com.android.systemui"));
    assertTrue(
        SecureCapturePolicy.recording(
            true, true, true, "com.android.systemui", "Recording Display"));
  }

  @Test
  public void masterAndGuardAlwaysWin() {
    for (boolean master : new boolean[] {false, true})
      for (boolean guard : new boolean[] {false, true}) {
        if (master && guard) continue;
        assertFalse(SecureCapturePolicy.screenshot(master, true, guard, "com.android.systemui"));
        assertFalse(
            SecureCapturePolicy.recording(
                master, true, guard, "com.samsung.android.app.smartcapture", "ScreenRecorder"));
      }
  }

  @Test
  public void castsOtherDisplaysAndScreenshotPreflightDoNotInheritShotSetting() {
    assertFalse(
        SecureCapturePolicy.screenshot(true, true, true, "com.samsung.android.app.smartcapture"));
    assertFalse(SecureCapturePolicy.screenshot(true, true, true, "com.example.app"));
    assertFalse(SecureCapturePolicy.recording(true, true, true, "com.android.systemui", "Cast"));
    assertFalse(
        SecureCapturePolicy.recording(true, true, true, "com.example.app", "ScreenRecorder"));
    assertTrue(
        SecureCapturePolicy.recording(
            true, true, true, "com.samsung.android.app.smartcapture", "ScreenRecorder"));
  }

  @Test
  public void onlyFlagSecureMayBeOverridden() {
    assertTrue(SecureCapturePolicy.onlySecureFlag(16, false, false));
    for (int reason : new int[] {0, 1, 2, 4, 8, 32, 48})
      assertFalse(SecureCapturePolicy.onlySecureFlag(reason, false, false));
    assertFalse(SecureCapturePolicy.onlySecureFlag(16, true, false));
    assertFalse(SecureCapturePolicy.onlySecureFlag(16, false, true));
  }
}
