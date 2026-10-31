package dev.ryuya.galaxycapture

import org.junit.Assert.*
import org.junit.Test

open class AudioLevelsTest {
    @Test
    open fun inputsHaveIndependentLevelsBeforeNativeMixAndClipping() {
        val media = shortArrayOf(32000, -32000, 8000, -8000)
        val mic = shortArrayOf(12000, -12000, 4000, -4000)
        AudioLevels.scale(media, 4, 25)
        AudioLevels.scale(mic, 4, 50)
        assertArrayEquals(shortArrayOf(8000, -8000, 2000, -2000), media)
        assertArrayEquals(shortArrayOf(6000, -6000, 2000, -2000), mic)
    }

    @Test
    open fun silenceIsExactAndNeverReadsBeyondValidNativeSamples() {
        val input = shortArrayOf(-32768, 32767, 1234, 5678)
        AudioLevels.scale(input, 2, 0)
        assertArrayEquals(shortArrayOf(0, 0, 1234, 5678), input)
        AudioLevels.scale(input, -1, 0)
        assertEquals(1234, input[2].toInt())
    }

    @Test
    open fun fullLevelIsBitExactAndOutOfRangeValuesCannotAmplifyOrInvert() {
        val input = shortArrayOf(-32768, 32767, -1, 0, 1)
        val original = input.clone()
        AudioLevels.scale(input, 999, 150)
        assertArrayEquals(original, input)
        AudioLevels.scale(input, 999, -20)
        assertArrayEquals(ShortArray(5), input)
        assertEquals(100, AudioLevels.clamp(1000))
        assertEquals(0, AudioLevels.clamp(-1))
    }
}
