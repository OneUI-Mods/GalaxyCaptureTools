package dev.ryuya.galaxycapture;

import android.os.Bundle;

record Options(
    boolean enabled,
    boolean rotation,
    boolean samsungApp,
    boolean singleWindow,
    boolean appAudioOnly,
    boolean ignoreHalfTurns) {
  static final Options OFF = new Options(false, false, false, false, true, false);

  static Options from(Bundle b) {
    return b == null
        ? OFF
        : new Options(
            b.getBoolean("enabled", false),
            b.getBoolean("rotation", true),
            b.getBoolean("samsung_app", true),
            b.getBoolean("single_window", false),
            b.getBoolean("app_audio_only", true),
            b.getBoolean("ignore_half_turns", false));
  }
}
