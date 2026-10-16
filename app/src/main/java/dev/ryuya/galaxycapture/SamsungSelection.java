package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.content.*;
import android.media.projection.*;
import android.os.*;
import de.robv.android.xposed.*;

final class SamsungSelection {
  record Target(Object cookie, int taskId, int uid, boolean audioOnly) {}

  private static volatile Target target;
  private static MediaProjection earlyProjection;
  private static Object earlyOwner;
  private static Class<?> projectionWrapper;
  private static boolean picking, approved;

  static Target target() {
    return target;
  }

  static void installSystemUi(HookRuntime rt, ClassLoader loader) {
    Class<?> recorder =
        XposedHelpers.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader);
    XposedBridge.hookAllMethods(
        recorder,
        "start",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled() || !rt.options.singleWindow()) return;
            try {
              Bundle b =
                  rt.context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
              if (b == null || !b.getBoolean("framework_ready", false))
                p.setThrowable(
                    new java.io.IOException("Window recording requires framework reload"));
            } catch (Throwable error) {
              p.setThrowable(new java.io.IOException("Window recording unavailable", error));
            }
          }
        });
    AudioScope.installAosp(rt, loader);
  }

  static void installRecorder(HookRuntime rt, ClassLoader loader) {
    SamsungRecordingUi.install(rt, loader);
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
            SamsungRecordingUi.State state = SamsungRecordingUi.state(p.thisObject);
            if (state == null || state.mode < 2) {
              target = null;
              return;
            }
            p.setResult(null);
            if (picking) return;
            if (state.selected == null) {
              cancel(p.thisObject);
              return;
            }
            picking = true;
            target = null;
            Object owner = p.thisObject;
            Bundle request = new Bundle();
            request.putString("id", state.selected);
            request.putBoolean("windows", state.mode == 3);
            ResultReceiver result =
                new ResultReceiver(new Handler(Looper.getMainLooper())) {
                  boolean done;

                  @Override
                  protected void onReceiveResult(int code, Bundle result) {
                    if (done) return;
                    done = true;
                    picking = false;
                    if (code != Activity.RESULT_OK
                        || result == null
                        || !rt.options.enabled()
                        || !rt.guard.allowed()) {
                      cancel(owner);
                      rt.report("Selected target unavailable; recording canceled");
                      return;
                    }
                    try {
                      IBinder binder = result.getBinder("cookie");
                      int task = result.getInt("task_id", -1), uid = result.getInt("uid", -1);
                      if (binder == null || uid < 0)
                        throw new IllegalStateException("Invalid capture target");
                      Object cookie =
                          XposedHelpers.newInstance(
                              XposedHelpers.findClass(
                                  "android.app.ActivityOptions$LaunchCookie", null),
                              binder);
                      target = new Target(cookie, task, uid, state.audioOnly);
                      rt.guard.arm();
                      approved = true;
                      XposedHelpers.callMethod(owner, "startCountDownTimer");
                      rt.report("Selected target; countdown started; app audio=" + state.audioOnly);
                    } catch (Throwable error) {
                      approved = false;
                      cancel(owner);
                      rt.fail(error);
                    }
                  }
                };
            try {
              TargetBridge.request(rt, "activate", request, result);
              new Handler(Looper.getMainLooper())
                  .postDelayed(() -> result.send(Activity.RESULT_CANCELED, Bundle.EMPTY), 5000);
            } catch (Throwable error) {
              picking = false;
              cancel(owner);
              rt.fail(error);
            }
          }
        });
    projectionWrapper =
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.baseutil.sep.SepWrapper$MediaProjectionManager",
            loader);
    XposedBridge.hookAllMethods(
        projectionWrapper,
        "semGetMediaProjection",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (earlyProjection != null) p.setResult(earlyProjection);
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Target t = target;
            if (t == null || p.hasThrowable() || p.getResult() == null) return;
            try {
              Object impl = XposedHelpers.callMethod(p.getResult(), "getProjection");
              XposedHelpers.callMethod(impl, "setLaunchCookie", t.cookie());
              XposedHelpers.callMethod(impl, "setTaskId", t.taskId());
            } catch (Throwable error) {
              try {
                XposedHelpers.callMethod(p.getResult(), "stop");
              } catch (Throwable ignored) {
              }
              p.setThrowable(new IllegalStateException("Capture target rejected", error));
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
            earlyProjection = null;
            earlyOwner = null;
            rt.guard.finish();
          }
        });
    AudioScope.installSamsung(rt, loader);
  }

  static synchronized MediaProjection audioProjection(Object owner) {
    if (owner == earlyOwner && earlyProjection != null) return earlyProjection;
    MediaProjection projection =
        (MediaProjection) XposedHelpers.getObjectField(owner, "mMediaProjection");
    if (projection == null) {
      Context c = (Context) XposedHelpers.getObjectField(owner, "mContext");
      projection =
          (MediaProjection)
              XposedHelpers.callStaticMethod(
                  projectionWrapper,
                  "semGetMediaProjection",
                  c.getSystemService(MediaProjectionManager.class),
                  0);
      if (projection == null) throw new IllegalStateException("Audio projection unavailable");
      XposedHelpers.setObjectField(owner, "mMediaProjection", projection);
    }
    earlyOwner = owner;
    earlyProjection = projection;
    return projection;
  }

  private static void cancel(Object owner) {
    target = null;
    approved = false;
    try {
      ((android.app.Service) XposedHelpers.getObjectField(owner, "mScreenRecorderService"))
          .stopSelf();
    } catch (Throwable ignored) {
    }
  }
}
