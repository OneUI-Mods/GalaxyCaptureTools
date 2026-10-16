package dev.ryuya.galaxycapture;

import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.*;

final class TargetBridge {
  static final String ACTION = "dev.ryuya.galaxycapture.TARGETS";

  static void request(HookRuntime rt, String operation, Bundle data, ResultReceiver receiver) {
    Intent intent =
        new Intent(ACTION)
            .setPackage("android")
            .putExtra("operation", operation)
            .putExtra("receiver", receiver);
    if (data != null) intent.putExtra("data", data);
    rt.context.sendBroadcast(
        intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
  }

  static void requireReady(HookRuntime rt) throws Exception {
    var latch = new java.util.concurrent.CountDownLatch(1);
    boolean[] ready = {false};
    request(
        rt,
        "health",
        null,
        new ResultReceiver(rt.worker) {
          @Override
          protected void onReceiveResult(int code, Bundle result) {
            ready[0] = code == android.app.Activity.RESULT_OK;
            latch.countDown();
          }
        });
    if (!latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) || !ready[0])
      throw new IllegalStateException("System framework unavailable");
  }
}
