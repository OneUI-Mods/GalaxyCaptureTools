package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.content.*;
import android.media.projection.*;
import android.os.*;
import dev.ryuya.galaxycapture.hook.*;

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
        Reflect.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader);
    Hooks.hookAllMethods(
        recorder,
        "start",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled() || !rt.options.singleWindow()) return;
            try {
              TargetBridge.requireReady(rt);
            } catch (Throwable error) {
              p.setThrowable(new java.io.IOException("Window recording unavailable", error));
            }
          }
        });
    RecordingIndicator.install(rt, loader);
    AospRecordingUi.install(rt, loader);
    AudioScope.installAosp(rt, loader);
  }

  static void installRecorder(HookRuntime rt, ClassLoader loader) {
    CaptureControls.install(rt, loader);
    RecordingVolume.install(rt, loader);
    SecureCapture.installRecorder(rt, loader);
    SamsungRecordingUi.install(rt, loader);
    Class<?> controller =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", loader);
    Hooks.hookAllMethods(
        controller,
        "startCountDownTimer",
        new HookCallback() {
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
                          Reflect.newInstance(
                              Reflect.findClass("android.app.ActivityOptions$LaunchCookie", null),
                              binder);
                      target = new Target(cookie, task, uid, state.audioOnly);
                      rt.guard.arm();
                      approved = true;
                      Reflect.callMethod(owner, "startCountDownTimer");
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

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.hasThrowable()
                || !rt.options.enabled()
                || rt.options.countdown()
                || !rt.guard.allowed()) return;
            try {
              // Selection/consent must have reached the native countdown. A canceled or pending
              // asynchronous selection has no timer and cannot start recording here.
              CountDownTimer timer =
                  (CountDownTimer) Reflect.getObjectField(p.thisObject, "mCountDownTimer");
              if (timer == null) return;
              timer.cancel();
              // Its own completion removes both progress timers and the countdown window,
              // creates the native controls, and starts the recorder. No delayed tick survives.
              timer.onFinish();
            } catch (Throwable error) {
              cancel(p.thisObject);
              rt.fail(error);
            }
          }
        });
    projectionWrapper =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.baseutil.sep.SepWrapper$MediaProjectionManager",
            loader);
    Hooks.hookAllMethods(
        projectionWrapper,
        "semGetMediaProjection",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (earlyProjection != null) p.setResult(earlyProjection);
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Target t = target;
            if (t == null || p.hasThrowable() || p.getResult() == null) return;
            try {
              Object impl = Reflect.callMethod(p.getResult(), "getProjection");
              Reflect.callMethod(impl, "setLaunchCookie", t.cookie());
              Reflect.callMethod(impl, "setTaskId", t.taskId());
            } catch (Throwable error) {
              try {
                Reflect.callMethod(p.getResult(), "stop");
              } catch (Throwable ignored) {
              }
              p.setThrowable(new IllegalStateException("Capture target rejected", error));
              rt.fail(error);
            }
          }
        });
    Class<?> recorder =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder",
            loader);
    Hooks.hookAllMethods(
        recorder,
        "stopVirtualDisplay",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            target = null;
            approved = false;
            earlyProjection = null;
            earlyOwner = null;
            rt.guard.finish();
            if (rt.guard.allowed()) rt.report("Ready; recording ended");
          }
        });
    AudioScope.installSamsung(rt, loader);
  }

  static synchronized MediaProjection audioProjection(Object owner) {
    if (owner == earlyOwner && earlyProjection != null) return earlyProjection;
    MediaProjection projection =
        (MediaProjection) Reflect.getObjectField(owner, "mMediaProjection");
    if (projection == null) {
      Context c = (Context) Reflect.getObjectField(owner, "mContext");
      projection =
          (MediaProjection)
              Reflect.callStaticMethod(
                  projectionWrapper,
                  "semGetMediaProjection",
                  c.getSystemService(MediaProjectionManager.class),
                  0);
      if (projection == null) throw new IllegalStateException("Audio projection unavailable");
      Reflect.setObjectField(owner, "mMediaProjection", projection);
    }
    earlyOwner = owner;
    earlyProjection = projection;
    return projection;
  }

  private static void cancel(Object owner) {
    target = null;
    approved = false;
    try {
      ((android.app.Service) Reflect.getObjectField(owner, "mScreenRecorderService")).stopSelf();
    } catch (Throwable ignored) {
    }
  }
}
