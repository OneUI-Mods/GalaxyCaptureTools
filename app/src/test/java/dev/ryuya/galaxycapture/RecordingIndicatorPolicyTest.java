package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import org.junit.Test;

public final class RecordingIndicatorPolicyTest {
  private static final String SAMSUNG = "com.samsung.android.app.smartcapture";
  private static final String CHANNEL = "CHANNEL_ID_RECORDING_SCREEN";

  @Test
  public void onlyRunningGalaxyRecordingCanMove() {
    assertTrue(
        RecordingIndicatorPolicy.hideLeft(true, true, true, true, SAMSUNG, 1332, CHANNEL, true));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, true, true, true, SAMSUNG, 1334, CHANNEL, false));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, true, true, true, SAMSUNG, 1332, CHANNEL, false));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, true, true, true, SAMSUNG, 1332, "other", true));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(
            true, true, true, true, "com.android.systemui", 1332, CHANNEL, true));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, true, true, true, null, 1332, CHANNEL, true));
  }

  @Test
  public void missingRightIconAndEveryDisabledStateKeepLeftIndicator() {
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(false, true, true, true, SAMSUNG, 1332, CHANNEL, true));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, false, true, true, SAMSUNG, 1332, CHANNEL, true));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, true, false, true, SAMSUNG, 1332, CHANNEL, true));
    assertFalse(
        RecordingIndicatorPolicy.hideLeft(true, true, true, false, SAMSUNG, 1332, CHANNEL, true));
  }

  @Test
  public void recordingIconDoesNotConsumeAnotherNotificationOverflowSlot() {
    assertEquals(0, RecordingIndicatorPolicy.visibleIndex(0, new int[] {0}));
    assertEquals(2, RecordingIndicatorPolicy.visibleIndex(3, new int[] {0}));
    assertEquals(3, RecordingIndicatorPolicy.visibleIndex(3, new int[] {4}));
    assertEquals(1, RecordingIndicatorPolicy.visibleIndex(3, new int[] {0, 2}));
    assertEquals(3, RecordingIndicatorPolicy.visibleIndex(3, new int[0]));
  }
}
