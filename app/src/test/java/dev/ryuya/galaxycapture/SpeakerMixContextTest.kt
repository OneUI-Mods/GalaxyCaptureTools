package dev.ryuya.galaxycapture

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

open class SpeakerMixContextTest {
    @Test
    open fun originalBehaviorOutsideMixCallback() {
        assertFalse(SpeakerMixContext.includePlayback(Any()))
        assertFalse(SpeakerMixContext.includePlayback(null))
    }

    @Test
    open fun overrideBelongsOnlyToTheCurrentRecorderAndEndsOnException() {
        val owner = Any()
        val previous = SpeakerMixContext.enter(owner)
        try {
            assertTrue(SpeakerMixContext.includePlayback(owner))
            assertFalse(SpeakerMixContext.includePlayback(Any()))
            throw IllegalStateException("simulated native mixing failure")
        } catch (expected: IllegalStateException) {} finally {
            SpeakerMixContext.restore(previous)
        }
        assertFalse(SpeakerMixContext.includePlayback(owner))
    }

    @Test
    @Throws(Exception::class)
    open fun anotherThreadKeepsRealHeadphoneState() {
        val owner = Any()
        val previous = SpeakerMixContext.enter(owner)
        try {
            val overridden = AtomicBoolean(true)
            val route =
                Thread(Runnable { overridden.set(SpeakerMixContext.includePlayback(owner)) })
            route.start()
            route.join()
            assertFalse(overridden.get())
            assertTrue(SpeakerMixContext.includePlayback(owner))
        } finally {
            SpeakerMixContext.restore(previous)
        }
    }

    @Test
    open fun nestedCallbacksRestoreTheirOwner() {
        val first = Any()
        val second = Any()
        val outer = SpeakerMixContext.enter(first)
        try {
            val inner = SpeakerMixContext.enter(second)
            try {
                assertFalse(SpeakerMixContext.includePlayback(first))
                assertTrue(SpeakerMixContext.includePlayback(second))
            } finally {
                SpeakerMixContext.restore(inner)
            }
            assertTrue(SpeakerMixContext.includePlayback(first))
        } finally {
            SpeakerMixContext.restore(outer)
        }
        assertFalse(SpeakerMixContext.includePlayback(first))
    }
}
