package dev.ryuya.galaxycapture

/* Rotation relative to the recording start; half-turn suppression works for either start axis. */
class RotationPolicy(initial: Int, fill: Boolean, ignoreHalfTurns: Boolean) {
    private val initial: Int
    private val fill: Boolean
    private val ignoreHalfTurns: Boolean
    private var swappedTurn: Int = 0

    init {
        this.initial = Math.floorMod(initial, 4)
        this.fill = fill
        this.ignoreHalfTurns = ignoreHalfTurns
    }

    fun update(rotation: Int): Int {
        val delta = Math.floorMod(rotation - initial, 4)
        // Without quarter-turn correction, native logical content stays upright and letterboxed.
        // Keep that native mapping when suppressing half-turns independently of black-bar removal.
        if (!fill) return 0
        if (!ignoreHalfTurns) return delta
        if (delta % 2 == 0) return 0
        if (swappedTurn == 0) swappedTurn = delta
        return swappedTurn
    }
}
