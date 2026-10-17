package dev.ryuya.galaxycapture;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.LinearLayout;
import dev.ryuya.galaxycapture.hook.*;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Moves the actual Samsung recording chip, including its native gesture detector and stop dialog.
 */
final class RightRecordingIndicator {
  private final HookRuntime rt;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<Object, State> states = new WeakHashMap<>();
  private Class<?> nativeChip;
  private boolean failed;

  private RightRecordingIndicator(HookRuntime runtime) {
    rt = runtime;
  }

  static void install(HookRuntime rt, ClassLoader loader) {
    RightRecordingIndicator bridge = new RightRecordingIndicator(rt);
    try {
      bridge.install(loader);
    } catch (Throwable error) {
      bridge.fail(error);
    }
  }

  private void install(ClassLoader loader) throws Exception {
    Class<?> fragment =
        Reflect.findClass(
            "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment", loader);
    Class<?> controller =
        Reflect.findClass(
            "com.android.systemui.statusbar.phone.ongoingcast.SamsungOngoingCastController",
            loader);
    nativeChip =
        Reflect.findClass("com.android.systemui.statusbar.phone.TouchInterceptFrameLayout", loader);
    if (!ViewGroup.class.isAssignableFrom(nativeChip)
        || nativeChip.getDeclaredField("customClickListener").getType()
            != View.OnClickListener.class
        || nativeChip.getDeclaredField("touchForwardView").getType() != View.class)
      throw new IllegalStateException("Native interactive chip contract changed");
    fragment.getDeclaredMethod("onViewCreated", View.class, Bundle.class);
    fragment.getDeclaredMethod("onDestroyView");
    controller.getDeclaredMethod("updateAll", String.class);
    Hooks.hookAllMethods(
        fragment,
        "onViewCreated",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (failed || p.hasThrowable()) return;
            try {
              Object owner = Reflect.getObjectField(p.thisObject, "mOngoingCastController");
              View root = (View) Reflect.getObjectField(p.thisObject, "mStatusBar");
              View chip = (View) Reflect.getObjectField(p.thisObject, "mOngoingCastChip");
              if (!controller.isInstance(owner)
                  || !nativeChip.isInstance(chip)
                  || !"samsung_ongoing_cast_chip".equals(name(chip)))
                throw new IllegalStateException("Recording chip binding changed");
              int id =
                  root.getResources()
                      .getIdentifier("status_bar_end_side_content", "id", "com.android.systemui");
              View end = id == 0 ? null : root.findViewById(id);
              if (!(end instanceof LinearLayout target)
                  || target.getOrientation() != LinearLayout.HORIZONTAL
                  || !(chip.getParent() instanceof LinearLayout original))
                throw new IllegalStateException("Status-bar chip containers changed");
              State prior = states.remove(p.thisObject);
              if (prior != null) prior.destroy();
              State state = new State(root, chip, original, target, owner);
              states.put(p.thisObject, state);
              root.addOnLayoutChangeListener(state.layout);
              state.queue();
            } catch (Throwable error) {
              fail(error);
            }
          }
        });
    Hooks.hookAllMethods(
        fragment,
        "onDestroyView",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = states.remove(p.thisObject);
            if (state != null) state.destroy();
          }
        });
    Hooks.hookAllMethods(
        controller,
        "updateAll",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (failed || p.hasThrowable()) return;
            for (State state : new ArrayList<>(states.values()))
              if (state.owner.get() == p.thisObject) state.queue();
          }
        });
    rt.onOptionsChanged(
        () ->
            main.post(
                () -> {
                  for (State state : new ArrayList<>(states.values())) {
                    state.fitRejected = false;
                    state.queue();
                  }
                }));
    Hooks.log("GalaxyCapture: native Samsung recording chip relocation ready");
  }

  private static String name(View view) {
    try {
      return view.getResources().getResourceEntryName(view.getId());
    } catch (android.content.res.Resources.NotFoundException ignored) {
      return "";
    }
  }

  private boolean samsungRecording(Object controller) {
    if (Reflect.getBooleanField(controller, "isLinkToWindowing")) return false;
    Object raw =
        Reflect.callMethod(Reflect.getObjectField(controller, "castController"), "getCastDevices");
    if (!(raw instanceof List<?> devices))
      throw new IllegalStateException("Cast device list changed");
    boolean recording = false;
    for (Object device : devices) {
      if (!Reflect.getBooleanField(device, "isCasting")) continue;
      Object tag = Reflect.getObjectField(device, "tag");
      if (tag == null
          || !"android.media.projection.MediaProjectionInfo".equals(tag.getClass().getName())
          || !"com.samsung.android.app.smartcapture"
              .equals(Reflect.callMethod(tag, "getPackageName"))) return false;
      recording = true;
    }
    return recording;
  }

  private void fail(Throwable error) {
    failed = true;
    for (State state : new ArrayList<>(states.values())) state.restore();
    rt.report("Ready; recordingChip=unavailable: " + error.getClass().getSimpleName());
    Hooks.log("GalaxyCapture: chip relocation disabled; native controls retained: " + error);
  }

  private final class State {
    final WeakReference<View> root, chip;
    final WeakReference<LinearLayout> original, target;
    final WeakReference<Object> owner;
    final int index;
    final ViewGroup.LayoutParams originalParams;
    final View.OnLayoutChangeListener layout;
    final Runnable apply = this::apply;
    boolean moved, moving, fitRejected, destroyed;
    String diagnostic = "";

    State(View bar, View view, LinearLayout start, LinearLayout end, Object controller) {
      root = new WeakReference<>(bar);
      chip = new WeakReference<>(view);
      original = new WeakReference<>(start);
      target = new WeakReference<>(end);
      owner = new WeakReference<>(controller);
      index = start.indexOfChild(view);
      originalParams = view.getLayoutParams();
      layout =
          (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitRejected = false;
            queue();
          };
    }

    void queue() {
      main.removeCallbacks(apply);
      if (!destroyed) main.post(apply);
    }

    void apply() {
      if (moving || destroyed) return;
      try {
        View bar = root.get(), view = chip.get();
        LinearLayout start = original.get(), end = target.get();
        Object controller = owner.get();
        boolean on =
            !failed
                && rt.options.enabled()
                && rt.guard.allowed()
                && rt.options.recordingIconRight()
                && !fitRejected;
        if (!on
            || bar == null
            || view == null
            || start == null
            || end == null
            || controller == null
            || !bar.isAttachedToWindow()
            || bar.getDisplay() == null
            || bar.getDisplay().getDisplayId() != 0
            || bar.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT
            || view.getRootView() != end.getRootView()
            || !samsungRecording(controller)) {
          restore();
          report(fitRejected ? "left-space-limited" : "left");
          return;
        }
        if (Reflect.getObjectField(controller, "chipView") != view
            || Reflect.getObjectField(view, "customClickListener") == null) {
          restore();
          report("waiting-native-click");
          return;
        }
        if (!moved) {
          if (view.getParent() != start
              || !(originalParams instanceof LinearLayout.LayoutParams params))
            throw new IllegalStateException("Chip parent changed");
          LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(params);
          next.width = ViewGroup.LayoutParams.WRAP_CONTENT;
          next.weight = 0;
          moving = true;
          start.removeView(view);
          try {
            end.addView(view, 0, next);
            moved = true;
          } catch (Throwable error) {
            start.addView(view, Math.min(index, start.getChildCount()), originalParams);
            throw error;
          } finally {
            moving = false;
          }
          // It is the identical View instance; no click listener, clock or dialog is replaced.
          queue();
          report("right; nativeView=true; nativeClick=true");
        } else if (view.getParent() == end
            && view.getVisibility() == View.VISIBLE
            && view.getWidth() > 0) {
          Rect visible = new Rect();
          if (view.isShown()
              && (!view.getGlobalVisibleRect(visible)
                  || visible.width() < view.getWidth()
                  || visible.height() < view.getHeight())) {
            fitRejected = true;
            restore();
            report("left-space-limited");
          }
        }
      } catch (Throwable error) {
        fail(error);
      }
    }

    void report(String value) {
      if (value.equals(diagnostic)) return;
      diagnostic = value;
      rt.report("Ready; recordingChip=" + value);
      Hooks.log("GalaxyCapture: recordingChip=" + value);
    }

    void restore() {
      View view = chip.get();
      LinearLayout start = original.get(), end = target.get();
      if (!moved || moving || view == null || start == null) return;
      moving = true;
      try {
        if (view.getParent() == end) {
          end.removeView(view);
          start.addView(view, Math.min(index, start.getChildCount()), originalParams);
        }
        moved = false;
      } catch (Throwable error) {
        // Preserve the interactive view even if an external layout changes during restoration.
        if (view.getParent() == null && end != null) {
          try {
            end.addView(view);
          } catch (Throwable ignored) {
          }
        }
        Hooks.log("GalaxyCapture: native chip restoration failed: " + error);
      } finally {
        moving = false;
      }
    }

    void destroy() {
      destroyed = true;
      main.removeCallbacks(apply);
      View bar = root.get();
      if (bar != null) bar.removeOnLayoutChangeListener(layout);
      restore();
    }
  }
}
