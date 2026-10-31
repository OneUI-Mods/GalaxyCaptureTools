package dev.ryuya.galaxycapture

import android.app.Activity
import android.app.BroadcastOptions
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmStatic

object TargetBridge {
    const val ACTION: String = "dev.ryuya.galaxycapture.TARGETS"

    @JvmStatic
    fun request(rt: HookRuntime, operation: String?, data: Bundle?, receiver: ResultReceiver?) {
        val intent =
            Intent(TargetBridge.ACTION)
                .setPackage("android")
                .putExtra("operation", operation)
                .putExtra("receiver", receiver)
        if (data != null) intent.putExtra("data", data)
        rt.context.sendBroadcast(
            intent,
            null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun requireReady(rt: HookRuntime) {
        val latch = CountDownLatch(1)
        val ready = booleanArrayOf(false)
        TargetBridge.request(
            rt,
            "health",
            null,
            object : ResultReceiver(rt.worker) {
                override fun onReceiveResult(code: Int, result: Bundle?) {
                    ready[0] = code == Activity.RESULT_OK
                    latch.countDown()
                }
            },
        )
        check(!(!latch.await(1500, TimeUnit.MILLISECONDS) || !ready[0])) {
            "System framework unavailable"
        }
    }
}
