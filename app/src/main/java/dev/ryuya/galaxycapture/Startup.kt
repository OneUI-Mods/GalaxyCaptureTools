package dev.ryuya.galaxycapture

import android.content.Context
import android.content.Intent
import android.os.UserManager
import kotlin.jvm.JvmStatic

/* CE settings stay untouched until unlock; reboot never overrides OFF or crash protection. */
object Startup {
    @JvmStatic
    fun restore(context: Context) {
        if (!context.getSystemService<UserManager?>(UserManager::class.java).isUserUnlocked())
            return
        context.getSharedPreferences("config", Context.MODE_PRIVATE) // Start the settings process.
        Startup.notifyHosts(context)
    }

    @JvmStatic
    fun notifyHosts(context: Context) {
        for (pkg in
            arrayOf<String>(
                "android",
                "com.android.systemui",
                "com.samsung.android.app.smartcapture",
            )) context.sendBroadcast(Intent(HookRuntime.CONFIG_CHANGED).setPackage(pkg))
    }
}
