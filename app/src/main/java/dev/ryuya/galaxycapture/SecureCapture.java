package dev.ryuya.galaxycapture;

import android.app.admin.DevicePolicyManager;
import android.graphics.Bitmap;
import android.os.Binder;
import dev.ryuya.galaxycapture.hook.*;

/** Preserve FLAG_SECURE on source windows, Recents, casts, and unrelated capture requests. */
final class SecureCapture {
  private static final ThreadLocal<Shot> screenshot = new ThreadLocal<>();
  private static final ThreadLocal<Object> projection = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> preflight = new ThreadLocal<>();

  private static final class Shot {
    final Object controller;
    boolean allowed;

    Shot(Object controller) {
      this.controller = controller;
    }
  }

  private static boolean enabled(HookRuntime rt) {
    return rt != null && rt.options.enabled() && rt.guard.allowed();
  }

  private static boolean owner(HookRuntime rt, int uid, String pkg) {
    String[] names = rt.context.getPackageManager().getPackagesForUid(uid);
    return names != null && java.util.Arrays.asList(names).contains(pkg);
  }

  static void installFramework(ClassLoader loader) {
    Class<?> shots = Reflect.findClass("com.android.server.wm.WmScreenshotController", loader);
    Hooks.hookAllMethods(
        shots,
        "takeScreenshotToTargetWindow",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            HookRuntime rt = FrameworkHooks.runtime;
            if (!enabled(rt) || !rt.options.secureScreenshot()) return;
            try {
              if (!owner(rt, Binder.getCallingUid(), "com.android.systemui")) return;
              rt.guard.beginCapture();
              p.setObjectExtra("previousShot", screenshot.get());
              p.setObjectExtra("shotEntered", true);
              screenshot.set(new Shot(p.thisObject));
            } catch (Throwable e) {
              rt.fail(e);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!Boolean.TRUE.equals(p.getObjectExtra("shotEntered"))) return;
            Shot previous = (Shot) p.getObjectExtra("previousShot");
            if (previous == null) screenshot.remove();
            else screenshot.set(previous);
            FrameworkHooks.runtime.guard.endCapture();
            if (p.hasThrowable()) FrameworkHooks.runtime.fail(p.getThrowable());
          }
        });
    Hooks.hookAllMethods(
        shots,
        "isScreenshotAllowedByPolicy",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Shot shot = screenshot.get();
            HookRuntime rt = FrameworkHooks.runtime;
            if (shot == null || shot.controller != p.thisObject || !enabled(rt) || p.hasThrowable())
              return;
            try {
              boolean sensitive =
                  Reflect.getBooleanField(p.thisObject, "mIsBlockedBySensitiveContents");
              DevicePolicyManager policy = rt.context.getSystemService(DevicePolicyManager.class);
              boolean disabled = policy == null || policy.getScreenCaptureDisabled(null);
              int reason = Reflect.getIntField(p.thisObject, "mReasonForFailure");
              if (SecureCapturePolicy.onlySecureFlag(reason, sensitive, disabled)) {
                Reflect.setIntField(p.thisObject, "mReasonForFailure", reason & ~16);
                p.setResult(true);
              }
              shot.allowed = Boolean.TRUE.equals(p.getResult()) && !sensitive && !disabled;
            } catch (Throwable e) {
              rt.fail(e);
            }
          }
        });
    Hooks.hookAllMethods(
        shots,
        "screenshot",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Shot shot = screenshot.get();
            if (shot != null
                && shot.controller == p.thisObject
                && shot.allowed
                && enabled(FrameworkHooks.runtime)) p.args[5] = true;
          }
        });

    // Stay inside the already-authorized recorder's createVirtualDisplay Binder request.
    // Neither projection consent/validity checks nor source surfaces are modified.
    Class<?> displays =
        Reflect.findClass("com.android.server.display.DisplayManagerService$BinderService", loader);
    Hooks.hookAllMethods(
        displays,
        "createVirtualDisplay",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            HookRuntime rt = FrameworkHooks.runtime;
            if (!enabled(rt)
                || !rt.options.secureRecording()
                || p.args.length != 4
                || p.args[2] == null) return;
            try {
              String pkg = (String) p.args[3];
              String name = (String) Reflect.callMethod(p.args[0], "getName");
              if (!SecureCapturePolicy.recording(true, true, true, pkg, name)
                  || !owner(rt, Binder.getCallingUid(), pkg)) return;
              rt.guard.beginCapture();
              p.setObjectExtra("previousProjection", projection.get());
              p.setObjectExtra("projectionEntered", true);
              projection.set(p.args[2]);
            } catch (Throwable e) {
              rt.fail(e);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!Boolean.TRUE.equals(p.getObjectExtra("projectionEntered"))) return;
            Object previous = p.getObjectExtra("previousProjection");
            if (previous == null) projection.remove();
            else projection.set(previous);
            FrameworkHooks.runtime.guard.endCapture();
            if (p.hasThrowable()) FrameworkHooks.runtime.fail(p.getThrowable());
          }
        });
    Class<?> token =
        Reflect.findClass(
            "com.android.server.media.projection.MediaProjectionManagerService$MediaProjection",
            loader);
    Hooks.hookAllMethods(
        token,
        "applyVirtualDisplayFlags",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.thisObject == projection.get()
                && enabled(FrameworkHooks.runtime)
                && !p.hasThrowable()) p.setResult((int) p.getResult() | 4);
          }
        });
    Hooks.hookAllMethods(
        token,
        "canProjectSecureVideo",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.thisObject == projection.get()
                && enabled(FrameworkHooks.runtime)
                && !p.hasThrowable()) p.setResult(true);
          }
        });
    for (java.lang.reflect.Method method :
        Reflect.findClass("com.android.server.display.DisplayManagerService", loader)
            .getDeclaredMethods())
      if (method.getName().contains("createVirtualDisplayInternal")) Hooks.deoptimize(method);
    for (java.lang.reflect.Method method : shots.getDeclaredMethods())
      if (method.getName().equals("takeScreenshotToTargetWindow")) Hooks.deoptimize(method);
  }

  static void installRecorder(HookRuntime rt, ClassLoader loader) {
    Class<?> controller =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", loader);
    Hooks.hookAllMethods(
        controller,
        "checkPreConditions",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previousPreflight", preflight.get());
            preflight.set(enabled(rt) && rt.options.secureRecording());
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Boolean previous = (Boolean) p.getObjectExtra("previousPreflight");
            if (previous == null) preflight.remove();
            else preflight.set(previous);
          }
        });
    // The preflight bitmap is only a recording eligibility probe; never saved as a screenshot.
    // Keep the battery, storage, calls, consent, and native recording-start flow intact.
    Hooks.hookAllMethods(
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.baseutil.view.CaptureUtils", loader),
        "captureScreen",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!Boolean.TRUE.equals(preflight.get()) || !enabled(rt) || p.hasThrowable()) return;
            if (p.getResult() == null) {
              Bitmap probe = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
              probe.eraseColor(android.graphics.Color.WHITE);
              p.setResult(probe);
            }
          }
        });
  }
}
