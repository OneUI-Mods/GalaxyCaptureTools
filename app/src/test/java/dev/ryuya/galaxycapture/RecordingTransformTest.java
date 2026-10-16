package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecordingTransformTest {
  @Test
  public void portraitToLandscapeFillsTheOriginalPortraitFrame() {
    assertFills(3120, 1440, 1440, 3120, 1);
    assertFills(3120, 1440, 1440, 3120, 3);
  }

  @Test
  public void landscapeToPortraitFillsTheOriginalLandscapeFrame() {
    assertFills(1440, 3120, 3120, 1440, 1);
    assertFills(1440, 3120, 3120, 1440, 3);
  }

  @Test
  public void upsideDownKeepsAllCornersInFrame() {
    assertFills(1440, 3120, 1440, 3120, 2);
  }

  @Test
  public void reducedEncodingResolutionKeepsAllEdges() {
    for (int q = 0; q < 4; q++)
      assertFills(q % 2 == 0 ? 1440 : 3120, q % 2 == 0 ? 3120 : 1440, 720, 1560, q);
  }

  @Test
  public void distinctCornerOrderSurvivesEveryRotation() {
    for (int q = 0; q < 4; q++) {
      var t = RecordingTransform.fit(200, 100, q % 2 == 0 ? 200 : 100, q % 2 == 0 ? 100 : 200, q);
      assertTrue(t.a() * t.d() - t.b() * t.c() > 0);
      assertEquals(1, Math.hypot(t.a(), t.b()), .0001);
      assertEquals(1, Math.hypot(t.c(), t.d()), .0001);
    }
  }

  @Test
  public void unrelatedAspectRatioIsFittedWithoutDistortionOrCropping() {
    var t = RecordingTransform.fit(100, 100, 100, 200, 0);
    assertEquals(1, t.a(), 0);
    assertEquals(1, t.d(), 0);
    assertEquals(50, t.y(), 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void emptySourcesAreRejected() {
    RecordingTransform.fit(0, 10, 10, 10, 0);
  }

  @Test
  public void wrappedRotationHasTheSameMapping() {
    assertEquals(
        RecordingTransform.fit(30, 50, 50, 30, 1), RecordingTransform.fit(30, 50, 50, 30, 5));
    assertEquals(
        RecordingTransform.fit(30, 50, 50, 30, 3), RecordingTransform.fit(30, 50, 50, 30, -1));
  }

  private static void assertFills(int w, int h, int ow, int oh, int q) {
    var t = RecordingTransform.fit(w, h, ow, oh, q);
    float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
    for (int x : new int[] {0, w})
      for (int y : new int[] {0, h}) {
        float px = t.mapX(x, y), py = t.mapY(x, y);
        minX = Math.min(minX, px);
        minY = Math.min(minY, py);
        maxX = Math.max(maxX, px);
        maxY = Math.max(maxY, py);
      }
    assertEquals(0, minX, .001);
    assertEquals(0, minY, .001);
    assertEquals(ow, maxX, .001);
    assertEquals(oh, maxY, .001);
  }
}
