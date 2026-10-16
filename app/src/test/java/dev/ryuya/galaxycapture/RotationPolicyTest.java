package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import org.junit.Test;

public class RotationPolicyTest {
  @Test
  public void portraitStartKeepsOppositeLandscapeTurnsIdentical() {
    var p = new RotationPolicy(0, true, true);
    assertEquals(0, p.update(0));
    assertEquals(1, p.update(1));
    assertEquals(1, p.update(3));
    assertEquals(0, p.update(2));
    assertEquals(0, p.update(0));
  }

  @Test
  public void firstCounterclockwiseTurnIsKeptAcrossHalfTurns() {
    var p = new RotationPolicy(0, true, true);
    assertEquals(3, p.update(3));
    assertEquals(3, p.update(1));
    assertEquals(0, p.update(0));
    assertEquals(3, p.update(1));
  }

  @Test
  public void landscapeStartKeepsOppositePortraitTurnsIdentical() {
    var p = new RotationPolicy(1, true, true);
    assertEquals(0, p.update(3));
    assertEquals(3, p.update(0));
    assertEquals(3, p.update(2));
    assertEquals(0, p.update(1));
  }

  @Test
  public void landscapeStartSuppressionAlsoWorksWithoutBlackBarCorrection() {
    var p = new RotationPolicy(1, false, true);
    for (int r : new int[] {1, 3, 0, 2, 3, 1}) assertEquals(0, p.update(r));
  }

  @Test
  public void disablingSuppressionPreservesAllFourDirections() {
    for (int initial = 0; initial < 4; initial++) {
      var p = new RotationPolicy(initial, true, false);
      for (int r = 0; r < 4; r++) assertEquals(Math.floorMod(r - initial, 4), p.update(r));
    }
  }
}
