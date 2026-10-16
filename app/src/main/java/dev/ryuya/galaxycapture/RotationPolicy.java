package dev.ryuya.galaxycapture;

/** Rotation relative to the recording start; half-turn suppression works for either start axis. */
final class RotationPolicy {
  private final int initial;
  private final boolean fill, ignoreHalfTurns;
  private int swappedTurn;

  RotationPolicy(int initial, boolean fill, boolean ignoreHalfTurns) {
    this.initial = Math.floorMod(initial, 4);
    this.fill = fill;
    this.ignoreHalfTurns = ignoreHalfTurns;
  }

  int update(int rotation) {
    int delta = Math.floorMod(rotation - initial, 4);
    // Without quarter-turn correction, native logical content stays upright and letterboxed.
    // Keep that native mapping when suppressing half-turns independently of black-bar removal.
    if (!fill) return 0;
    if (!ignoreHalfTurns) return delta;
    if (delta % 2 == 0) return 0;
    if (swappedTurn == 0) swappedTurn = delta;
    return swappedTurn;
  }
}
