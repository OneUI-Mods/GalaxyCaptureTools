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
}
