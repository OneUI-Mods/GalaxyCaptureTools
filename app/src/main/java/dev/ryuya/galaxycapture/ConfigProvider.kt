package dev.ryuya.galaxycapture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.Settings.Global
import java.util.Arrays
import kotlin.jvm.JvmField
import kotlin.math.min

class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun call(method: String, arg: String?, data: Bundle?): Bundle? {
        val c = getContext()
        if (c == null) return Bundle.EMPTY
        if (method == "config") {
            val p = c.getSharedPreferences("config", 0)
            val b = Bundle()
            for (setting in CaptureSettings.all()) b.putBoolean(
                setting!!.key,
                p.getBoolean(setting!!.key, setting!!.defaultValue),
            )
            for (k in arrayOf<String>("media_volume", "mic_volume")) b.putInt(
                k,
                AudioLevels.clamp(p.getInt(k, 100)),
            )
            b.putString("reset", p.getString("reset", ""))
            val status = c.getSharedPreferences("status", 0)
            val boot =
                Global.getInt(
                    c.getContentResolver(),
                    Global.BOOT_COUNT,
                    -1,
                )
            val server = status.getString("framework", "")
            b.putBoolean(
                "framework_ready",
                boot >= 0 &&
                    status.getInt("framework_boot", -2) == boot &&
                    (server!!.startsWith("Ready") || server!!.startsWith("Recording")),
            )
            return b
        }
        val uid = Binder.getCallingUid()
        val packages = c.getPackageManager().getPackagesForUid(uid)
        val host: Boolean =
            uid == 1000 ||
                (packages != null &&
                    (Arrays.asList<String?>(*packages).contains("com.android.systemui") ||
                        Arrays.asList<String?>(*packages)
                            .contains("com.samsung.android.app.smartcapture")))
        if (
            method == "native_settings" &&
                packages != null &&
                Arrays.asList<String?>(*packages)
                    .contains("com.samsung.android.app.smartcapture") &&
                data != null
        ) {
            // Only Samsung Capture can write the explicit user-facing setting allowlist.
            val edit = c.getSharedPreferences("config", 0).edit()
            for (key in arrayOf<String>("media_volume", "mic_volume")) if (data.containsKey(key))
                edit.putInt(key, AudioLevels.clamp(data.getInt(key)))
            for (setting in CaptureSettings.all()) if (data.containsKey(setting!!.key))
                edit.putBoolean(setting!!.key, data.getBoolean(setting!!.key))
            edit.apply()
            Startup.notifyHosts(c)
            return Bundle.EMPTY
        }
        if (method == "status" && host && arg != null) {
            val hostName: String? =
                if (uid == 1000) "framework"
                else if (Arrays.asList<String?>(*packages).contains("com.android.systemui"))
                    "systemui"
                else "samsung"
            c.getSharedPreferences("status", 0)
                .edit()
                .putString(hostName, arg.substring(0, min(1500, arg.length)))
                .putInt(
                    hostName + "_boot",
                    Global.getInt(
                        c.getContentResolver(),
                        Global.BOOT_COUNT,
                        -1,
                    ),
                )
                .putLong(hostName + "_time", System.currentTimeMillis())
                .apply()
            return Bundle.EMPTY
        }
        throw SecurityException("Unsupported provider operation")
    }

    override fun query(
        u: Uri,
        p: Array<String?>?,
        s: String?,
        a: Array<String?>?,
        o: String?,
    ): Cursor? {
        throw UnsupportedOperationException()
    }

    override fun getType(u: Uri): String? {
        return null
    }

    override fun insert(u: Uri, v: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(u: Uri, s: String?, a: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String?>?): Int {
        throw UnsupportedOperationException()
    }

    companion object {
        @JvmField val URI: Uri? = Uri.parse("content://dev.ryuya.galaxycapture.config")
        const val PERMISSION: String = "dev.ryuya.galaxycapture.permission.CONFIG"
    }
}
