package dev.ryuya.galaxycapture

import java.util.Properties
import kotlin.jvm.JvmStatic

/* Pure restart accounting, shared with the durable host guard. */
object RestartLedger {
    @JvmStatic
    fun observe(state: Properties, now: Long, pid: Int) {
        val interrupted: Boolean =
            state.getProperty("active", "false").toBoolean() &&
                state.getProperty("pid", "") != pid.toString()
        if (interrupted) {
            val last = state.getProperty("time", "0").toLong()
            val count: Int =
                if (now >= last && now - last < 120000) state.getProperty("count", "0").toInt() + 1
                else 1
            state.setProperty("count", count.toString())
            if (count >= 3) state.setProperty("blocked", "true")
        }
        state.setProperty("active", "false")
        state.setProperty("pid", pid.toString())
    }
}
