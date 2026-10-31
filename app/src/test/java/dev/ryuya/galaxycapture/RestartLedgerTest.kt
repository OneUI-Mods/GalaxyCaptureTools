package dev.ryuya.galaxycapture

import java.util.Properties
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class RestartLedgerTest {
    @Test
    open fun threeInterruptedRecordingsStopFutureHooks() {
        val p = Properties()
        for (i in 1..3) {
            RestartLedgerTest.arm(p, i, (1000 * i).toLong())
            RestartLedger.observe(p, (1000 * i + 10).toLong(), i + 1)
            assertEquals(i >= 3, p.getProperty("blocked", "false").toBoolean())
        }
        assertEquals("3", p.getProperty("count"))
    }

    @Test
    open fun completedRecordingDoesNotCountAProcessRestart() {
        val p = Properties()
        RestartLedgerTest.arm(p, 1, 1000)
        p.setProperty("active", "false")
        RestartLedger.observe(p, 1010, 2)
        assertEquals("0", p.getProperty("count", "0"))
    }

    @Test
    open fun sameProcessCannotBeCountedTwice() {
        val p = Properties()
        RestartLedgerTest.arm(p, 1, 1000)
        RestartLedger.observe(p, 1010, 1)
        assertEquals("0", p.getProperty("count", "0"))
    }

    @Test
    open fun olderFailuresDoNotMakeAnUnrelatedRestartLookLikeALoop() {
        val p = Properties()
        p.setProperty("count", "2")
        RestartLedgerTest.arm(p, 1, 1000)
        RestartLedger.observe(p, 121000, 2)
        assertEquals("1", p.getProperty("count"))
        assertFalse(p.getProperty("blocked", "false").toBoolean())
    }

    @Test
    open fun clockRollbackDoesNotCompleteAFalseCrashLoop() {
        val p = Properties()
        p.setProperty("count", "2")
        RestartLedgerTest.arm(p, 1, 1000)
        RestartLedger.observe(p, 900, 2)
        assertEquals("1", p.getProperty("count"))
        assertFalse(p.getProperty("blocked", "false").toBoolean())
    }

    @Test
    open fun anExistingStopSurvivesNormalRestartsAndTimePassing() {
        val p = Properties()
        p.setProperty("blocked", "true")
        RestartLedger.observe(p, 999999, 3)
        assertTrue(p.getProperty("blocked").toBoolean())
        RestartLedger.observe(p, 1999999, 4)
        assertTrue(p.getProperty("blocked").toBoolean())
    }

    @Test
    open fun observingARecoveredProcessDoesNotKeepItArmed() {
        val p = Properties()
        RestartLedgerTest.arm(p, 1, 1000)
        RestartLedger.observe(p, 1010, 2)
        RestartLedger.observe(p, 1020, 3)
        assertEquals("1", p.getProperty("count"))
    }

    companion object {
        @JvmStatic
        private fun arm(p: Properties, pid: Int, now: Long) {
            p.setProperty("active", "true")
            p.setProperty("pid", pid.toString())
            p.setProperty("time", now.toString())
        }
    }
}
