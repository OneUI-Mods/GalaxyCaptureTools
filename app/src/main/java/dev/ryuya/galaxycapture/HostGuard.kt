package dev.ryuya.galaxycapture

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import kotlin.jvm.Synchronized
import kotlin.math.max

/* Durable before the first recording mutation; an unclean recording marks a restart. */
class HostGuard(context: Context, host: String) {
    private val file: AtomicFile
    private val state: Properties = Properties()
    private var blocked: Boolean = false
    private var recordingActive: Boolean = false
    private var captureOperations: Int = 0

    init {
        val base: File? =
            if (host == "framework") File("/data/system/galaxycapture/guard.properties")
            else
                File(
                    context.createDeviceProtectedStorageContext().getFilesDir(),
                    "galaxycapture-guard.properties",
                )
        file = AtomicFile(base)
        try {
            if (
                file.getBaseFile().exists() || File(file.getBaseFile().toString() + ".bak").exists()
            ) {
                if (
                    file.getBaseFile().length() > 8192 ||
                        File(file.getBaseFile().toString() + ".bak").length() > 8192
                )
                    throw IOException()
                file.openRead().use { `in` ->
                    state.load(`in`)
                }
            }
            val now = System.currentTimeMillis()
            val pid = Process.myPid()
            RestartLedger.observe(state, now, pid)
            blocked = state.getProperty("blocked", "false").toBoolean()
            save()
        } catch (error: Throwable) {
            blocked = true
        }
    }

    @Synchronized
    fun allowed(): Boolean {
        return !blocked
    }

    @Synchronized
    fun reset(token: String) {
        if (token.isEmpty() || token == state.getProperty("reset", "")) return
        state.setProperty("reset", token)
        state.setProperty("blocked", "false")
        state.setProperty("count", "0")
        state.setProperty("active", "false")
        blocked = false
        try {
            save()
        } catch (error: Throwable) {
            blocked = true
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun arm() {
        if (blocked) throw IOException("Guard blocked")
        recordingActive = true
        state.setProperty("active", "true")
        state.setProperty("time", System.currentTimeMillis().toString())
        save()
    }

    @Synchronized
    fun finish() {
        recordingActive = false
        state.setProperty("active", (captureOperations > 0).toString())
        try {
            save()
        } catch (error: Throwable) {
            blocked = true
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun beginCapture() {
        if (blocked) throw IOException("Guard blocked")
        captureOperations++
        state.setProperty("active", "true")
        state.setProperty("time", System.currentTimeMillis().toString())
        save()
    }

    @Synchronized
    fun endCapture() {
        captureOperations = max(0, captureOperations - 1)
        state.setProperty("active", (recordingActive || captureOperations > 0).toString())
        try {
            save()
        } catch (error: Throwable) {
            blocked = true
        }
    }

    @Synchronized
    fun fail() {
        blocked = true
        state.setProperty("blocked", "true")
        state.setProperty("active", "false")
        try {
            save()
        } catch (ignored: Throwable) {}
    }

    @Throws(IOException::class)
    private fun save() {
        val parent = file.getBaseFile().getParentFile()
        if (!parent.isDirectory() && !parent.mkdirs()) throw IOException("Guard directory")
        parent.setReadable(false, false)
        parent.setWritable(false, false)
        parent.setExecutable(false, false)
        parent.setReadable(true, true)
        parent.setWritable(true, true)
        parent.setExecutable(true, true)
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            state.store(out, "Galaxy Capture crash protection")
            file.finishWrite(out)
        } catch (error: Throwable) {
            if (out != null) file.failWrite(out)
            throw IOException("Guard persistence", error)
        }
    }
}
