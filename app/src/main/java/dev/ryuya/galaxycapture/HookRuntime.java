package dev.ryuya.galaxycapture;

import android.content.*;
import android.os.*;
import dev.ryuya.galaxycapture.hook.Hooks;

final class HookRuntime {
  static final String CONFIG_CHANGED = "dev.ryuya.galaxycapture.CONFIG_CHANGED";
  final Context context;
  final Handler worker;
  final HostGuard guard;
  volatile Options options = Options.OFF;
  volatile boolean frameworkReady;
  private final java.util.List<Runnable> optionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
  void onOptionsChanged(Runnable listener) { optionListeners.add(listener); }
  private void notifyOptions() {
    for (Runnable listener : optionListeners) {
      try { listener.run(); } catch (Throwable error) { Hooks.log("GalaxyCapture option listener: " + error); }
    }
  }
  private int configRetries;
  private final Runnable configRetry = this::reload;

  HookRuntime(Context c, String host) {
    context = c;
    var thread = new HandlerThread("GalaxyCaptureConfig");
    thread.start();
    worker = new Handler(thread.getLooper());
    guard = new HostGuard(c, host);
    var receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context c, Intent i) {
        worker.post(() -> {
          configRetries = 0;
          reload();
        });
      }
    };
    c.registerReceiver(receiver, new IntentFilter(CONFIG_CHANGED), ConfigProvider.PERMISSION,
        worker, Context.RECEIVER_EXPORTED);
    c.registerReceiver(new BroadcastReceiver() {
      @Override
      public void onReceive(Context c, Intent i) {
        worker.post(() -> {
          configRetries = 0;
          reload();
        });
      }
    }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, worker, Context.RECEIVER_NOT_EXPORTED);
    worker.post(() -> reload());
  }

  private void reload() {
    worker.removeCallbacks(configRetry);
    if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
      options = Options.OFF;
      notifyOptions();
      return; // USER_UNLOCKED resumes without polling encrypted storage.
    }
    try {
      var b = context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
      if (b == null)
        throw new IllegalStateException("Settings unavailable");
      configRetries = 0;
      guard.reset(b.getString("reset", ""));
      frameworkReady = b != null && b.getBoolean("framework_ready", false);
      options = guard.allowed() ? Options.from(b) : Options.OFF;
      report(guard.allowed() ? "Ready; " + options : "Stopped by crash protection");
    } catch (Throwable error) {
      options = Options.OFF;
      report("Waiting for settings");
      // The provider can start after an already-unlocked soft reboot. No root is needed.
      if (configRetries < 7)
        worker.postDelayed(configRetry, Math.min(30000L, 1000L << configRetries++));
    } finally { notifyOptions(); }
  }

  void report(String value) {
    worker.post(() -> {
      try {
        context.getContentResolver().call(ConfigProvider.URI, "status", value, null);
      } catch (Throwable ignored) {
      }
    });
  }

  void fail(Throwable error) {
    options = Options.OFF;
    guard.fail();
    notifyOptions();
    Hooks.log("GalaxyCapture: stopped: " + error);
    report("Stopped after hook error: " + error.getClass().getSimpleName());
  }
}
