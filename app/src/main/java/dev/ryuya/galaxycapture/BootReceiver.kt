package dev.ryuya.galaxycapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

/* Boot only restores settings. Updates can recover old root helpers once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val app = context ?: return
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> Startup.restore(app)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Startup.restore(app, fromBoot = false)
                if (app.getSystemService(UserManager::class.java)?.isUserUnlocked != true ||
                    !ScreenshotAnalysisQuota.needsLegacyRecovery(app)
                ) return
                val pending = goAsync() ?: return
                Thread({
                    try {
                        ScreenshotAnalysisQuota.recoverLegacyReaders(app)
                    } finally {
                        pending.finish()
                    }
                }, "CaptureQuotaRecovery").start()
            }
        }
    }
}
