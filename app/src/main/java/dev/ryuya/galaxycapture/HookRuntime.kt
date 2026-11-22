package dev.ryuya.galaxycapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.os.UserManager
import dev.ryuya.galaxycapture.compat.DexSymbols
import dev.ryuya.galaxycapture.hook.Hooks
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField
import kotlin.math.log
import kotlin.math.min

class HookRuntime(c: Context, host: String?, configureSymbols: Boolean = true) {
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

    private val thread = HandlerThread("GalaxyCaptureConfig")
    private val tasks = ReentrantLock()
    private val receivers = ArrayList<BroadcastReceiver>()
    private val retireActions = ArrayList<() -> Unit>()
    @Volatile private var retired = false

    internal fun tryRetireStandby(save: () -> Unit): Boolean {
        if (!tryPauseWorker {
                save()
                true
            }) return false
        closeRetiredWorker()
        return true
    }

    internal fun tryPauseWorker(save: () -> Boolean): Boolean {
        if (!tasks.tryLock()) return false
        try {
            if (!save()) return false
            retired = true
            return true
        } finally {
            tasks.unlock()
        }
    }

    internal fun closeRetiredWorker() {
        worker.removeCallbacksAndMessages(null)
        thread.quit()
        for (action in retireActions.asReversed()) {
            runCatching(action).onFailure { Hooks.log("GalaxyCapture standby cleanup: ${it.javaClass.simpleName}") }
        }
        retireActions.clear()
        for (receiver in receivers) {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { Hooks.log("GalaxyCapture receiver cleanup: ${it.javaClass.simpleName}") }
        }
        receivers.clear()
        optionListeners.clear()
    }

    internal fun ownStandby(onRetire: () -> Unit) {
        retireActions.add(onRetire)
    }

    internal fun ownReceiver(receiver: BroadcastReceiver) {
        receivers.add(receiver)
    }

    init {
        context = c
        if (configureSymbols) DexSymbols.configure(c)
        thread.start()
        worker = object : Handler(thread.looper) {
            override fun dispatchMessage(message: Message) {
                tasks.withLock {
                    if (!retired) super.dispatchMessage(message)
                }
            }
        }
        guard = HostGuard(c, host!!)
        fun updateReceiver() = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (retired) return
                worker.post {
                    configRetries = 0
                    reload()
                }
            }
        }
        val optionsReceiver = updateReceiver()
        c.registerReceiver(optionsReceiver, IntentFilter(HookRuntime.CONFIG_CHANGED),
            ConfigProvider.PERMISSION, worker, Context.RECEIVER_EXPORTED)
        receivers.add(optionsReceiver)
        val unlockReceiver = updateReceiver()
        c.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
            null, worker, Context.RECEIVER_NOT_EXPORTED)
        receivers.add(unlockReceiver)
        worker.post(Runnable { reload() })
    }

    private fun reload() {
        worker.removeCallbacks(configRetry)
        if (
            context.getSystemService<UserManager?>(UserManager::class.java)?.isUserUnlocked() !=
                true
        ) {
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
            },
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
