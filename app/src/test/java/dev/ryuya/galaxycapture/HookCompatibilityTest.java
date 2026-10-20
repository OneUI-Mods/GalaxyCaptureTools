package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import org.junit.Test;

public class HookCompatibilityTest {
  @Test
  public void knownAndroid16StillUsesItsOriginalProfile() {
    assertEquals(HookCompatibility.Platform.ANDROID_16, HookCompatibility.platform(36));
    assertTrue(HookCompatibility.supports(36, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD));
  }

  @Test
  public void android17DoesNotReuseTheAndroid16Allowlist() {
    assertEquals(HookCompatibility.Platform.ANDROID_17, HookCompatibility.platform(37));
    assertFalse(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD));
    assertFalse(
        HookCompatibility.supports(
            37, "samsung", "SM-S948Q", "m3q", Android17Hooks.CANDIDATE_FIRMWARE));
    assertTrue(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", Android17Hooks.BUILD));
  }

  @Test
  public void android17AdmissionRejectsOtherHardwareAndBuilds() {
    assertFalse(HookCompatibility.supports(37, "other", "SM-S948Q", "m3q", Android17Hooks.BUILD));
    assertFalse(HookCompatibility.supports(37, "samsung", "other", "m3q", Android17Hooks.BUILD));
    assertFalse(
        HookCompatibility.supports(37, "samsung", "SM-S948Q", "other", Android17Hooks.BUILD));
    assertFalse(HookCompatibility.supports(37, "samsung", "SM-S948Q", "m3q", "other-firmware"));
    assertFalse(HookCompatibility.supports(36, "samsung", "SM-S948Q", "m3q", Android17Hooks.BUILD));
  }

  @Test
  public void futureAndOlderVersionsAreNotImplicitlyEnabled() {
    for (int sdk : new int[] {35, 38, 100}) {
      assertEquals(HookCompatibility.Platform.UNSUPPORTED, HookCompatibility.platform(sdk));
      assertFalse(
          HookCompatibility.supports(sdk, "samsung", "SM-S948Q", "m3q", Android16Hooks.BUILD));
    }
  }

  @Test
  public void nativeSamsungDialogBindingsAreVersionSpecific() {
    var old = HookCompatibility.samsungDialog(36);
    assertEquals("M4.c", old.builderClass());
    assertEquals("f", old.createMethod());
    var current = HookCompatibility.samsungDialog(37);
    assertEquals("androidx.recyclerview.widget.g2", current.builderClass());
    assertEquals("h", current.createMethod());
  }
}
