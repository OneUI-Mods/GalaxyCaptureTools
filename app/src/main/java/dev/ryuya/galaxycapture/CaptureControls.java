package dev.ryuya.galaxycapture;

import android.view.View;
import android.view.WindowManager;
import dev.ryuya.galaxycapture.hook.*;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Only the native recorder toolbar and post-screenshot toolbar windows are affected. */
final class CaptureControls {
  private static final String PREFIX = "com.samsung.android.app.smartcapture.";
  private static final String POLICY = "galaxycapture.controlsPolicy";
  private static final ThreadLocal<CaptureUiPolicy> creating = new ThreadLocal<>();
  private static final Map<View, CaptureUiPolicy> windows =
      Collections.synchronizedMap(new WeakHashMap<>());

  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> floating =
        Reflect.findClass(PREFIX + "screenrecorder.view.FloatingButtonView", loader);
    // A redraw during rotation retains the first policy for this recorder instance.
    Hooks.hookAllMethods(floating, "initView", scoped(rt, true));
    Hooks.hookAllMethods(floating, "showPointerGuidePopup", scoped(rt, true));
    Class<?> fragment = Reflect.findClass(PREFIX + "screenshot.fragment.ServiceFragment", loader);
    Hooks.hookAllMethods(
        fragment,
        "registerUi",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!p.thisObject
                .getClass()
                .getName()
                .equals(PREFIX + "screenshot.ServiceFragmentForStandAlone")) return;
            enter(p, CaptureUiPolicy.from(rt.options, false, rt.guard.allowed()));
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            leave(p);
          }
        });
    Class<?> global = Reflect.findClass("android.view.WindowManagerGlobal", null);
    Hooks.hookAllMethods(
        global,
        "addView",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            CaptureUiPolicy policy = creating.get();
            if (policy == null
                || p.args.length < 2
                || !(p.args[0] instanceof View root)
                || !(p.args[1] instanceof WindowManager.LayoutParams params)) return;
            try {
              apply(
                  params, policy); // Before the first relayout / surface exists, not after display.
              windows.put(root, policy);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
        });
    Hooks.hookAllMethods(
        global,
        "updateViewLayout",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (p.args.length < 2
                || !(p.args[0] instanceof View root)
                || !(p.args[1] instanceof WindowManager.LayoutParams params)) return;
            CaptureUiPolicy policy = windows.get(root);
            if (policy == null) return;
            try {
              apply(params, policy);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
        });
  }

  private static HookCallback scoped(HookRuntime rt, boolean recording) {
    return new HookCallback() {
      @Override
      protected void beforeHookedMethod(MethodHookParam p) {
        try {
          CaptureUiPolicy policy =
              (CaptureUiPolicy) Reflect.getAdditionalInstanceField(p.thisObject, POLICY);
          if (policy == null) {
            policy = CaptureUiPolicy.from(rt.options, recording, rt.guard.allowed());
            Reflect.setAdditionalInstanceField(p.thisObject, POLICY, policy);
          }
          enter(p, policy);
        } catch (Throwable error) {
          rt.fail(error);
        }
      }

      @Override
      protected void afterHookedMethod(MethodHookParam p) {
        leave(p);
      }
    };
  }

  private static void enter(HookCallback.MethodHookParam p, CaptureUiPolicy policy) {
    p.setObjectExtra("controlsPrevious", creating.get());
    p.setObjectExtra("controlsEntered", true);
    creating.set(policy);
  }

  private static void leave(HookCallback.MethodHookParam p) {
    if (!Boolean.TRUE.equals(p.getObjectExtra("controlsEntered"))) return;
    CaptureUiPolicy previous = (CaptureUiPolicy) p.getObjectExtra("controlsPrevious");
    if (previous == null) creating.remove();
    else creating.set(previous);
  }

  private static void apply(WindowManager.LayoutParams params, CaptureUiPolicy policy) {
    if (policy.exclude()) {
      int flags = Reflect.getIntField(params, "privateFlags");
      Reflect.setIntField(params, "privateFlags", policy.privateFlags(flags));
    }
    // Keep the native View and callbacks alive. Window alpha cannot be reset by View animations.
    // NOT_TOUCHABLE prevents an invisible full-screen input window; notification stop is untouched.
    params.flags = policy.flags(params.flags);
    params.alpha = policy.alpha(params.alpha);
  }
}
