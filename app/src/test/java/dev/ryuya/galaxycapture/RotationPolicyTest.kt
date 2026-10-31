package dev.ryuya.galaxycapture

import org.junit.Assert.*
import org.junit.Test

open class RotationPolicyTest {
    @Test
    open fun portraitStartKeepsOppositeLandscapeTurnsIdentical() {
        val p = RotationPolicy(0, true, true)
        assertEquals(0, p.update(0))
        assertEquals(1, p.update(1))
        assertEquals(1, p.update(3))
        assertEquals(0, p.update(2))
        assertEquals(0, p.update(0))
    }

    @Test
    open fun firstCounterclockwiseTurnIsKeptAcrossHalfTurns() {
        val p = RotationPolicy(0, true, true)
        assertEquals(3, p.update(3))
        assertEquals(3, p.update(1))
        assertEquals(0, p.update(0))
        assertEquals(3, p.update(1))
    }

    @Test
    open fun landscapeStartKeepsOppositePortraitTurnsIdentical() {
        val p = RotationPolicy(1, true, true)
        assertEquals(0, p.update(3))
        assertEquals(3, p.update(0))
        assertEquals(3, p.update(2))
        assertEquals(0, p.update(1))
    }

    @Test
    open fun landscapeStartSuppressionAlsoWorksWithoutBlackBarCorrection() {
        val p = RotationPolicy(1, false, true)
        for (r in intArrayOf(1, 3, 0, 2, 3, 1)) assertEquals(0, p.update(r))
    }

    @Test
    open fun disablingSuppressionPreservesAllFourDirections() {
        for (initial in 0..3) {
            val p = RotationPolicy(initial, true, false)
            for (r in 0..3) assertEquals(Math.floorMod(r - initial, 4), p.update(r))
        }
    }
}
