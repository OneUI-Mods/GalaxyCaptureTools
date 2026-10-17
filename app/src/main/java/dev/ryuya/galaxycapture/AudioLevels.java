package dev.ryuya.galaxycapture;

/** Attenuate native PCM inputs in place; no buffering, resampling, or gain above unity. */
record AudioLevels(boolean enabled, int media, int mic) {
  static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
  static AudioLevels from(Options o) {
    return new AudioLevels(o.enabled() && o.recordingVolume(), clamp(o.mediaVolume()), clamp(o.micVolume()));
  }
  static void scale(short[] data, int valid, int percent) {
    int level = clamp(percent);
    if (level == 100 || valid <= 0) return;
    for (int i = 0, end = Math.min(data.length, valid); i < end; i++)
      data[i] = (short) Math.round(data[i] * (level / 100f));
  }
}
