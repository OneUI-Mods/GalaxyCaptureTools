package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.content.*;
import android.os.*;
import de.robv.android.xposed.*;
import java.util.*;

final class SamsungSelection {
  private static final String SELECT = "dev.ryuya.galaxycapture.SELECT_APP",
      RECEIVER = "galaxy_capture_receiver",
      REQUEST = "galaxy_capture_request";
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final Set<String> pending = new HashSet<>();
  private static Target target;
  private static boolean picking, approved;

  private record Target(Object cookie, int taskId) {}

  static void installSystemUi(HookRuntime rt, ClassLoader loader) {
    Class<?> selector =
        XposedHelpers.findClass(
            "com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity",
            loader);
    Class<?> aospRecorder =
        XposedHelpers.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader);
    XposedBridge.hookAllMethods(
        aospRecorder,
        "start",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled() || !rt.options.singleWindow()) return;
            try {
              Bundle health =
                  rt.context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
              if (health == null || !health.getBoolean("framework_ready", false))
                p.setThrowable(
                    new java.io.IOException("Window capture requires system framework restart"));
            } catch (Throwable error) {
              p.setThrowable(new java.io.IOException("Window capture unavailable", error));
            }
          }
        });
    XposedBridge.hookAllMethods(
        selector,
        "returnSelectedApp",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Activity activity = (Activity) p.thisObject;
            Intent intent = activity.getIntent();
            String id = intent.getStringExtra(REQUEST);
            if (id == null) return;
            if (!pending.remove(id)) {
              activity.finish();
              p.setResult(null);
              return;
            }
            ResultReceiver receiver = intent.getParcelableExtra(RECEIVER, ResultReceiver.class);
            if (receiver == null) {
              activity.finish();
              p.setResult(null);
              return;
            }
            try {
              Bundle result = new Bundle();
              result.putBinder(
                  "cookie", (IBinder) XposedHelpers.getObjectField(p.args[0], "binder"));
              result.putInt("task_id", (int) p.args[1]);
              XposedHelpers.setBooleanField(activity, "taskSelected", true);
              receiver.send(Activity.RESULT_OK, result);
              activity.finish();
              p.setResult(null);
              rt.guard.finish();
              rt.report("AOSP app selected");
            } catch (Throwable error) {
              receiver.send(Activity.RESULT_CANCELED, Bundle.EMPTY);
              activity.finish();
              p.setResult(null);
              rt.fail(error);
            }
          }
        });
    XposedBridge.hookAllMethods(
        selector,
        "onDestroy",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Activity activity = (Activity) p.thisObject;
            String id = activity.getIntent().getStringExtra(REQUEST);
            if (activity.isFinishing() && id != null && pending.remove(id)) {
              ResultReceiver receiver =
                  activity.getIntent().getParcelableExtra(RECEIVER, ResultReceiver.class);
              if (receiver != null) receiver.send(Activity.RESULT_CANCELED, Bundle.EMPTY);
            }
          }
        });
    rt.context.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent request) {
            ResultReceiver result = request.getParcelableExtra(RECEIVER, ResultReceiver.class);
            if (result == null) return;
            if (!rt.options.enabled() || !rt.options.samsungApp() || !rt.guard.allowed()) {
              result.send(Activity.RESULT_CANCELED, Bundle.EMPTY);
              return;
            }
            String id = UUID.randomUUID().toString();
            pending.add(id);
            Intent chooser =
                new Intent()
                    .setClassName("com.android.systemui", selector.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("screen_share_type", "ScreenRecord")
                    .putExtra(RECEIVER, result)
                    .putExtra(REQUEST, id);
            try {
              chooser.putExtra("launched_from_user_handle", android.os.Process.myUserHandle());
              chooser.putExtra(
                  "launched_from_host_uid",
                  context
                      .getPackageManager()
                      .getApplicationInfo("com.samsung.android.app.smartcapture", 0)
                      .uid);
              rt.guard.arm();
              context.startActivity(chooser);
            } catch (Throwable error) {
              pending.remove(id);
              result.send(Activity.RESULT_CANCELED, Bundle.EMPTY);
              rt.fail(error);
            }
          }
        },
        new IntentFilter(SELECT),
        "android.permission.MANAGE_MEDIA_PROJECTION",
        main,
        Context.RECEIVER_EXPORTED);
  }

  static void installRecorder(HookRuntime rt, ClassLoader loader) {
    Class<?> controller =
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", loader);
    XposedBridge.hookAllMethods(
        controller,
        "startCountDownTimer",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (approved) {
              approved = false;
              return;
            }
            if (!rt.options.enabled() || !rt.options.samsungApp()) return;
            p.setResult(null);
            if (picking) return;
            picking = true;
            target = null;
            Object owner = p.thisObject;
            try {
              Bundle health =
                  rt.context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
              if (health == null || !health.getBoolean("framework_ready", false)) {
                picking = false;
                cancel(owner);
                rt.report("Restart required: system framework is unavailable");
                return;
              }
            } catch (Throwable error) {
              picking = false;
              cancel(owner);
              rt.fail(error);
              return;
            }
            ResultReceiver receiver =
                new ResultReceiver(main) {
                  private boolean done;

                  @Override
                  protected void onReceiveResult(int code, Bundle result) {
                    if (done) return;
                    done = true;
                    picking = false;
                    if (code != Activity.RESULT_OK
                        || result == null
                        || !rt.guard.allowed()
                        || !rt.options.enabled()) {
                      cancel(owner);
                      return;
                    }
                    try {
                      IBinder binder = result.getBinder("cookie");
                      int task = result.getInt("task_id", -1);
                      if (binder == null || task < 0)
                        throw new IllegalStateException("No selected app");
                      Class<?> cookie =
                          XposedHelpers.findClass("android.app.ActivityOptions$LaunchCookie", null);
                      target = new Target(XposedHelpers.newInstance(cookie, binder), task);
                      rt.guard.arm();
                      approved = true;
                      XposedHelpers.callMethod(owner, "startCountDownTimer");
                      rt.report("Selected app; countdown started");
                    } catch (Throwable error) {
                      approved = false;
                      target = null;
                      cancel(owner);
                      rt.fail(error);
                    }
                  }
                };
            try {
              rt.context.sendBroadcast(
                  new Intent(SELECT)
                      .setPackage("com.android.systemui")
                      .putExtra(RECEIVER, receiver));
              main.postDelayed(() -> receiver.send(Activity.RESULT_CANCELED, Bundle.EMPTY), 60000);
            } catch (Throwable error) {
              picking = false;
              cancel(owner);
              rt.fail(error);
            }
          }
        });
    Class<?> projection =
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.baseutil.sep.SepWrapper$MediaProjectionManager",
            loader);
    XposedBridge.hookAllMethods(
        projection,
        "semGetMediaProjection",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Target selected = target;
            if (selected == null || p.hasThrowable() || p.getResult() == null) return;
            try {
              Object impl = XposedHelpers.callMethod(p.getResult(), "getProjection");
              XposedHelpers.callMethod(impl, "setLaunchCookie", selected.cookie());
              XposedHelpers.callMethod(impl, "setTaskId", selected.taskId());
            } catch (Throwable error) {
              try {
                XposedHelpers.callMethod(p.getResult(), "stop");
              } catch (Throwable ignored) {
              }
              p.setThrowable(new IllegalStateException("App capture target rejected", error));
              rt.fail(error);
            }
          }
        });
    Class<?> recorder =
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder",
            loader);
    XposedBridge.hookAllMethods(
        recorder,
        "stopVirtualDisplay",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            target = null;
            approved = false;
            rt.guard.finish();
          }
        });
  }

  private static void cancel(Object controller) {
    target = null;
    approved = false;
    try {
      android.app.Service service =
          (android.app.Service) XposedHelpers.getObjectField(controller, "mScreenRecorderService");
      service.stopSelf();
    } catch (Throwable ignored) {
    }
  }
}
