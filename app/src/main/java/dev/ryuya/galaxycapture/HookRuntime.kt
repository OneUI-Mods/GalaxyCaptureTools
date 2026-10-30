package dev.ryuya.galaxycapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.UserManager
import dev.ryuya.galaxycapture.hook.Hooks
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.log
import kotlin.math.min

class HookRuntime(c: Context, host: String?) {
    @JvmField val context: Context
    @JvmField val worker: Handler
    @JvmField val guard: HostGuard
    @JvmField @Volatile var options: Options? = Options.OFF
    @JvmField @Volatile var frameworkReady: Boolean = false
    private val optionListeners: MutableList<Runnable?> = CopyOnWriteArrayList<Runnable?>()

    fun onOptionsChanged(listener: Runnable?) {
        optionListeners.add(listener)
    }

    private fun notifyOptions() {
        for (listener in optionListeners) {
            try {
                listener!!.run()
            } catch (error: Throwable) {
                Hooks.log("GalaxyCapture option listener: " + error)
            }
        }
    }

    private var configRetries: Int = 0
    private val configRetry: Runnable = Runnable { this.reload() }

    init {
        context = c
        val thread = HandlerThread("GalaxyCaptureConfig")
        thread.start()
        worker = Handler(thread.getLooper())
        guard = HostGuard(c, host!!)
        val receiver: BroadcastReceiver? =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    worker.post(
                        Runnable {
                            configRetries = 0
                            reload()
                        }
                    )
                }
            }
        c.registerReceiver(
            receiver,
            IntentFilter(HookRuntime.CONFIG_CHANGED),
            ConfigProvider.PERMISSION,
            worker,
            Context.RECEIVER_EXPORTED,
        )
        c.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    worker.post(
                        Runnable {
                            configRetries = 0
                            reload()
                        }
                    )
                }
            },
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            null,
            worker,
            Context.RECEIVER_NOT_EXPORTED,
        )
        worker.post(Runnable { reload() })
    }

    private fun reload() {
        worker.removeCallbacks(configRetry)
        if (!context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked()) {
            options = Options.OFF
            notifyOptions()
            return // USER_UNLOCKED resumes without polling encrypted storage.
        }
        try {
            val b = context.getContentResolver().call(ConfigProvider.URI!!, "config", null, null)
            checkNotNull(b) { "Settings unavailable" }
            configRetries = 0
            guard.reset(b.getString("reset", ""))
            frameworkReady = b != null && b.getBoolean("framework_ready", false)
            options = if (guard.allowed()) Options.from(b) else Options.OFF
            report(if (guard.allowed()) "Ready; " + options else "Stopped by crash protection")
        } catch (error: Throwable) {
            options = Options.OFF
            report("Waiting for settings")
            // The provider can start after an already-unlocked soft reboot. No root is needed.
            if (configRetries < 7)
                worker.postDelayed(configRetry, min(30000L, 1000L shl configRetries++))
        } finally {
            notifyOptions()
        }
    }

    fun report(value: String?) {
        worker.post(
            Runnable {
                try {
                    context.getContentResolver().call(ConfigProvider.URI!!, "status", value, null)
                } catch (ignored: Throwable) {}
            }
        )
    }

    fun fail(error: Throwable) {
        options = Options.OFF
        guard.fail()
        notifyOptions()
        Hooks.log("GalaxyCapture: stopped: " + error)
        report("Stopped after hook error: " + error.javaClass.getSimpleName())
    }

    companion object {
        const val CONFIG_CHANGED: String = "dev.ryuya.galaxycapture.CONFIG_CHANGED"
    }
}
