package dev.ryuya.galaxycapture

import org.junit.Assert.*
import org.junit.Test

open class SecureCapturePolicyTest {
    @Test
    open fun separateSwitchesCannotEnableEachOther() {
        assertTrue(SecureCapturePolicy.screenshot(true, true, true, "com.android.systemui"))
        assertFalse(
            SecureCapturePolicy.recording(
                true,
                false,
                true,
                "com.android.systemui",
                "Recording Display",
            )
        )
        assertFalse(SecureCapturePolicy.screenshot(true, false, true, "com.android.systemui"))
        assertTrue(
            SecureCapturePolicy.recording(
                true,
                true,
                true,
                "com.android.systemui",
                "Recording Display",
            )
        )
    }

    @Test
    open fun masterAndGuardAlwaysWin() {
        for (master in booleanArrayOf(false, true)) for (guard in booleanArrayOf(false, true)) {
            if (master && guard) continue
            assertFalse(SecureCapturePolicy.screenshot(master, true, guard, "com.android.systemui"))
            assertFalse(
                SecureCapturePolicy.recording(
                    master,
                    true,
                    guard,
                    "com.samsung.android.app.smartcapture",
                    "ScreenRecorder",
                )
            )
        }
    }

    @Test
    open fun castsOtherDisplaysAndScreenshotPreflightDoNotInheritShotSetting() {
        assertFalse(
            SecureCapturePolicy.screenshot(true, true, true, "com.samsung.android.app.smartcapture")
        )
        assertFalse(SecureCapturePolicy.screenshot(true, true, true, "com.example.app"))
        assertFalse(SecureCapturePolicy.recording(true, true, true, "com.android.systemui", "Cast"))
        assertFalse(
            SecureCapturePolicy.recording(true, true, true, "com.example.app", "ScreenRecorder")
        )
        assertTrue(
            SecureCapturePolicy.recording(
                true,
                true,
                true,
                "com.samsung.android.app.smartcapture",
                "ScreenRecorder",
            )
        )
    }

    @Test
    open fun onlyFlagSecureMayBeOverridden() {
        assertTrue(SecureCapturePolicy.onlySecureFlag(16, false, false))
        for (reason in intArrayOf(0, 1, 2, 4, 8, 32, 48)) assertFalse(
            SecureCapturePolicy.onlySecureFlag(reason, false, false)
        )
        assertFalse(SecureCapturePolicy.onlySecureFlag(16, true, false))
        assertFalse(SecureCapturePolicy.onlySecureFlag(16, false, true))
    }
}
