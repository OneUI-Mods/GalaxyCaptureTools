package dev.ryuya.galaxycapture;

import android.content.*;
import android.os.*;
import de.robv.android.xposed.XposedBridge;

final class HookRuntime {
  static final String CONFIG_CHANGED = "dev.ryuya.galaxycapture.CONFIG_CHANGED";
  final Context context;
  final Handler worker;
  final HostGuard guard;
  volatile Options options = Options.OFF;
  volatile boolean frameworkReady;

  HookRuntime(Context c, String host) {
    context = c;
    var thread = new HandlerThread("GalaxyCaptureConfig");
    thread.start();
    worker = new Handler(thread.getLooper());
    guard = new HostGuard(c, host);
    var receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            worker.post(() -> reload());
          }
        };
    c.registerReceiver(
        receiver,
        new IntentFilter(CONFIG_CHANGED),
        ConfigProvider.PERMISSION,
        worker,
        Context.RECEIVER_EXPORTED);
    c.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            worker.post(() -> reload());
          }
        },
        new IntentFilter(Intent.ACTION_USER_UNLOCKED),
        null,
        worker,
        Context.RECEIVER_NOT_EXPORTED);
    worker.post(() -> reload());
  }

  private void reload() {
    try {
      var b = context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
      guard.reset(b == null ? "" : b.getString("reset", ""));
      frameworkReady = b != null && b.getBoolean("framework_ready", false);
      options = guard.allowed() ? Options.from(b) : Options.OFF;
      report(guard.allowed() ? "Ready; " + options : "Stopped by crash protection");
    } catch (Throwable error) {
      options = Options.OFF;
      report("Waiting for settings");
    }
  }

  void report(String value) {
    worker.post(
        () -> {
          try {
            context.getContentResolver().call(ConfigProvider.URI, "status", value, null);
          } catch (Throwable ignored) {
          }
        });
  }

  void fail(Throwable error) {
    options = Options.OFF;
    guard.fail();
    XposedBridge.log("GalaxyCapture: stopped: " + error);
    report("Stopped after hook error: " + error.getClass().getSimpleName());
  }
}
