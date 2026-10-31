package dev.ryuya.galaxycapture

import kotlin.jvm.JvmStatic
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test

open class RecordingTransformTest {
    @Test
    open fun portraitToLandscapeFillsTheOriginalPortraitFrame() {
        RecordingTransformTest.assertFills(3120, 1440, 1440, 3120, 1)
        RecordingTransformTest.assertFills(3120, 1440, 1440, 3120, 3)
    }

    @Test
    open fun landscapeToPortraitFillsTheOriginalLandscapeFrame() {
        RecordingTransformTest.assertFills(1440, 3120, 3120, 1440, 1)
        RecordingTransformTest.assertFills(1440, 3120, 3120, 1440, 3)
    }

    @Test
    open fun upsideDownKeepsAllCornersInFrame() {
        RecordingTransformTest.assertFills(1440, 3120, 1440, 3120, 2)
    }

    @Test
    open fun reducedEncodingResolutionKeepsAllEdges() {
        for (q in 0..3) RecordingTransformTest.assertFills(
            if (q % 2 == 0) 1440 else 3120,
            if (q % 2 == 0) 3120 else 1440,
            720,
            1560,
            q,
        )
    }

    @Test
    open fun distinctCornerOrderSurvivesEveryRotation() {
        for (q in 0..3) {
            val t =
                RecordingTransform.fit(
                    200,
                    100,
                    if (q % 2 == 0) 200 else 100,
                    if (q % 2 == 0) 100 else 200,
                    q,
                )
            assertTrue(t!!.a * t!!.d - t!!.b * t!!.c > 0)
            assertEquals((1)!!.toDouble(), (hypot(t.a, t.b)).toDouble(), (.0001).toDouble())
            assertEquals((1)!!.toDouble(), (hypot(t.c, t.d)).toDouble(), (.0001).toDouble())
        }
    }

    @Test
    open fun unrelatedAspectRatioIsFittedWithoutDistortionOrCropping() {
        val t = RecordingTransform.fit(100, 100, 100, 200, 0)
        assertEquals((1)!!.toDouble(), (t!!.a).toDouble(), (0).toDouble())
        assertEquals((1)!!.toDouble(), (t!!.d).toDouble(), (0).toDouble())
        assertEquals((50)!!.toDouble(), (t!!.y).toDouble(), (0).toDouble())
    }

    @Test(expected = IllegalArgumentException::class)
    open fun emptySourcesAreRejected() {
        RecordingTransform.fit(0, 10, 10, 10, 0)
    }

    @Test
    open fun wrappedRotationHasTheSameMapping() {
        assertEquals(
            RecordingTransform.fit(30, 50, 50, 30, 1),
            RecordingTransform.fit(30, 50, 50, 30, 5),
        )
        assertEquals(
            RecordingTransform.fit(30, 50, 50, 30, 3),
            RecordingTransform.fit(30, 50, 50, 30, -1),
        )
    }

    companion object {
        @JvmStatic
        private fun assertFills(w: Int, h: Int, ow: Int, oh: Int, q: Int) {
            val t = RecordingTransform.fit(w, h, ow, oh, q)
            var minX: Float = Float.POSITIVE_INFINITY
            var minY: Float = minX
            var maxX: Float = Float.NEGATIVE_INFINITY
            var maxY: Float = maxX
            for (x in intArrayOf(0, w)) for (y in intArrayOf(0, h)) {
                val px = t!!.mapX(x.toFloat(), y.toFloat())
                val py = t!!.mapY(x.toFloat(), y.toFloat())
                minX = min(minX, px)
                minY = min(minY, py)
                maxX = max(maxX, px)
                maxY = max(maxY, py)
            }
            assertEquals((0).toDouble(), (minX).toDouble(), (.001).toDouble())
            assertEquals((0).toDouble(), (minY).toDouble(), (.001).toDouble())
            assertEquals((ow).toDouble(), (maxX).toDouble(), (.001).toDouble())
            assertEquals((oh).toDouble(), (maxY).toDouble(), (.001).toDouble())
        }
    }
}
