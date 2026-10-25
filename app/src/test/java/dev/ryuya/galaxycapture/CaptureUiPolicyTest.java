package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;
import org.junit.Test;

public class CaptureUiPolicyTest {
  private Options options(boolean enabled, boolean exclude, boolean hide) {
    return new Options(
        enabled, true, true, false, true, false, true, true, exclude, hide, true, false, false,
        false, 100, 100, false, false);
  }

  @Test
  public void masterAndCrashGuardPreserveNativeWindowAndInput() {
    for (CaptureUiPolicy policy :
        new CaptureUiPolicy[] {
          CaptureUiPolicy.from(options(false, true, true), true, true),
          CaptureUiPolicy.from(options(true, true, true), true, false)
        }) {
      assertEquals(0x20000, policy.privateFlags(0x20000));
      assertEquals(0x100, policy.flags(0x100));
      assertEquals(.8f, policy.alpha(.8f), 0f);
    }
  }

  @Test
  public void screenshotControlsRemainVisibleAndTouchableEvenWhenRecorderIsHidden() {
    CaptureUiPolicy policy = CaptureUiPolicy.from(options(true, true, true), false, true);
    assertEquals(0x120000, policy.privateFlags(0x20000));
    assertEquals(0x100, policy.flags(0x100));
    assertEquals(1f, policy.alpha(1f), 0f);
  }

  @Test
  public void exclusionPreservesNativeFlagsAndDoesNotAddSecureOrDisableInput() {
    CaptureUiPolicy policy = CaptureUiPolicy.from(options(true, true, false), true, true);
    assertEquals(0x101234, policy.privateFlags(0x1234));
    assertEquals(0x1000000, policy.flags(0x1000000));
    assertEquals(.7f, policy.alpha(.7f), 0f);
  }

  @Test
  public void hiddenRecordingWindowCannotInterceptTouches() {
    CaptureUiPolicy policy = CaptureUiPolicy.from(options(true, false, true), true, true);
    assertEquals(0x1234, policy.privateFlags(0x1234));
    assertEquals(0x118, policy.flags(0x100));
    assertEquals(0f, policy.alpha(1f), 0f);
  }

  @Test
  public void repeatedRotationRelayoutsAreIdempotent() {
    CaptureUiPolicy policy = CaptureUiPolicy.from(options(true, true, true), true, true);
    int flags = 0x1000000, privateFlags = 64;
    for (int n = 0; n < 50; n++) {
      flags = policy.flags(flags);
      privateFlags = policy.privateFlags(privateFlags);
    }
    assertEquals(0x1000018, flags);
    assertEquals(0x100040, privateFlags);
  }
}
