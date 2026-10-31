package dev.ryuya.galaxycapture

import org.junit.Assert.*
import org.junit.Test

open class CaptureUiPolicyTest {
    private fun options(enabled: Boolean, exclude: Boolean, hide: Boolean): Options? {
        return Options(
            enabled,
            true,
            true,
            false,
            true,
            false,
            true,
            true,
            exclude,
            hide,
            true,
            false,
            false,
            false,
            100,
            100,
            false,
            false,
        )
    }

    @Test
    open fun masterAndCrashGuardPreserveNativeWindowAndInput() {
        for (policy in
            arrayOf<CaptureUiPolicy?>(
                CaptureUiPolicy.from(options(false, true, true)!!, true, true),
                CaptureUiPolicy.from(options(true, true, true)!!, true, false),
            )) {
            assertEquals(0x20000, policy!!.privateFlags(0x20000))
            assertEquals(0x100, policy!!.flags(0x100))
            assertEquals((.8f).toFloat(), (policy!!.alpha(.8f)).toFloat(), (0f).toFloat())
        }
    }

    @Test
    open fun screenshotControlsRemainVisibleAndTouchableEvenWhenRecorderIsHidden() {
        val policy = CaptureUiPolicy.from(options(true, true, true)!!, false, true)
        assertEquals(0x120000, policy!!.privateFlags(0x20000))
        assertEquals(0x100, policy!!.flags(0x100))
        assertEquals((1f).toFloat(), (policy!!.alpha(1f)).toFloat(), (0f).toFloat())
    }

    @Test
    open fun exclusionPreservesNativeFlagsAndDoesNotAddSecureOrDisableInput() {
        val policy = CaptureUiPolicy.from(options(true, true, false)!!, true, true)
        assertEquals(0x101234, policy!!.privateFlags(0x1234))
        assertEquals(0x1000000, policy!!.flags(0x1000000))
        assertEquals((.7f).toFloat(), (policy!!.alpha(.7f)).toFloat(), (0f).toFloat())
    }

    @Test
    open fun hiddenRecordingWindowCannotInterceptTouches() {
        val policy = CaptureUiPolicy.from(options(true, false, true)!!, true, true)
        assertEquals(0x1234, policy!!.privateFlags(0x1234))
        assertEquals(0x118, policy!!.flags(0x100))
        assertEquals((0f).toFloat(), (policy!!.alpha(1f)).toFloat(), (0f).toFloat())
    }

    @Test
    open fun repeatedRotationRelayoutsAreIdempotent() {
        val policy = CaptureUiPolicy.from(options(true, true, true)!!, true, true)
        var flags: Int = 0x1000000
        var privateFlags: Int = 64
        for (n in 0..49) {
            flags = policy!!.flags(flags)
            privateFlags = policy!!.privateFlags(privateFlags)
        }
        assertEquals(0x1000018, flags)
        assertEquals(0x100040, privateFlags)
    }
}
