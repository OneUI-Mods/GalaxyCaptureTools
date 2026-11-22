package dev.ryuya.galaxycapture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.util.Log
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

internal object ScreenshotAnalysisQuota {
    private const val TAG = "CaptureAnalysisQuota"
    private const val CAPTURE = "com.samsung.android.app.smartcapture"
    const val WORKER_PROCESS = "$CAPTURE:ondemandscreenshotworker"
    const val REQUEST = "apply_screenshot_analysis_quota"
    private const val FLAG_NAME = "screenshot-analysis-quota-enabled"
    private const val SCRIPT = "screenshot-analysis-quota.sh"
    private val workers = mutableMapOf<Int, java.lang.Process>()
    private val hookCalls = ReentrantReadWriteLock()
    @Volatile private var retired = false

    fun tryRetireHook(save: () -> Unit): Boolean {
        val write = hookCalls.writeLock()
        if (!write.tryLock()) return false
        return try {
            save()
            retired = true
            true
        } finally {
            write.unlock()
        }
    }

    fun installWorkerHook(context: Context, loader: ClassLoader?): Boolean {
        try {
            val type = Reflect.findClass("$CAPTURE.solution.scheduling.OnDemandScreenshotWorker", loader)
            val method = type.getDeclaredMethod("startRemoteWork")
            if (method.returnType.name != "com.google.common.util.concurrent.ListenableFuture") return false
            Hooks.hook(method, object : HookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (retired) return
                    val read = hookCalls.readLock()
                    read.lock()
                    try {
                        if (!retired) {
                            runCatching {
                                context.contentResolver.call(ConfigProvider.URI!!, REQUEST, null, null)
                            }.onFailure { Hooks.log("GalaxyCapture analysis quota: ${it.javaClass.simpleName}") }
                        }
                    } finally {
                        read.unlock()
                    }
                }
            })
            return true
        } catch (error: Throwable) {
            Hooks.log("GalaxyCapture analysis quota unavailable: ${error.javaClass.simpleName}")
            return false
        }
    }

    private fun enabled(context: Context): Boolean {
        val preferences = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        return preferences.getBoolean("enabled", false) &&
            preferences.getBoolean(CaptureSettings.SCREENSHOT_ANALYSIS_CPU_LIMIT.key, false)
    }

    fun syncEnabled(context: Context) {
        runCatching {
            val flag = File(context.filesDir, FLAG_NAME)
            if (enabled(context)) flag.createNewFile() else flag.delete()
        }.onFailure { Log.w(TAG, "Could not update the quota setting", it) }
    }

    fun requestRoot() {
        Thread({
            try {
                val check = ProcessBuilder("su", "-c", "id -u").start()
                if (!check.waitFor(10, TimeUnit.SECONDS)) {
                    check.destroy()
                } else if (check.exitValue() != 0 || check.inputStream.bufferedReader().use { it.readLine() } != "0") {
                    Log.i(TAG, "Root access is unavailable")
                }
            } catch (_: Exception) {
                Log.i(TAG, "Root access is unavailable")
            }
        }, "CaptureQuotaRoot").start()
    }

    @Synchronized
    fun applyToCaller(context: Context, uid: Int, pid: Int): Boolean {
        if (pid <= 0 || UserHandle.getUserHandleForUid(uid) != Process.myUserHandle()) return false
        val target = try {
            context.packageManager.getApplicationInfo(CAPTURE, PackageManager.ApplicationInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        if (target.uid != uid || target.flags and ApplicationInfo.FLAG_SYSTEM == 0 || !enabled(context)) return false
        workers.entries.removeAll { !it.value.isAlive }
        if (workers.containsKey(pid)) return true
        val flag = File(context.filesDir, FLAG_NAME)
        var root: java.lang.Process? = null
        try {
            flag.createNewFile()
            val quotedFlag = "'" + flag.absolutePath.replace("'", "'\"'\"'") + "'"
            val process = ProcessBuilder("su", "-c", "sh -s -- $pid $uid $quotedFlag")
                .redirectErrorStream(true).start()
            root = process
            context.assets.open(SCRIPT).use { source -> process.outputStream.use { source.copyTo(it) } }
            val output = process.inputStream.bufferedReader()
            val ready = FutureTask {
                output.useLines { lines -> lines.any { it.startsWith("READY ") } }
            }
            Thread(ready, "CaptureQuotaReady").start()
            if (ready.get(900, TimeUnit.MILLISECONDS)) {
                workers[pid] = root
                Log.i(TAG, "Applied 10% CPU quota to the screenshot analysis worker")
                return true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not apply the screenshot analysis CPU quota", error)
        }
        root?.destroy()
        return false
    }
}
