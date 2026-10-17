package dev.ryuya.galaxycapture;

/** Recording notifications only: never suppress saved captures or another projection owner. */
final class RecordingIndicatorPolicy {
  static boolean hideLeft(
      boolean enabled,
      boolean rightRequested,
      boolean samsungRecording,
      boolean rightVisible,
      String pkg,
      int id,
      String channel,
      boolean ongoing) {
    return enabled
        && rightRequested
        && samsungRecording
        && rightVisible
        && ongoing
        && "com.samsung.android.app.smartcapture".equals(pkg)
        && id == 1332
        && "CHANNEL_ID_RECORDING_SCREEN".equals(channel);
  }

  static int visibleIndex(int index, int[] hidden) {
    int result = index;
    for (int position : hidden) if (position < index) result--;
    return Math.max(0, result);
  }
}
