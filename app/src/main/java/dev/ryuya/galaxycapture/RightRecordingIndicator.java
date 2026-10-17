package dev.ryuya.galaxycapture;

import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import dev.ryuya.galaxycapture.hook.*;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Uses Samsung's existing screen-record slot; notification content and stop actions are retained.
 */
final class RightRecordingIndicator {
  private final HookRuntime rt;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<ViewGroup, Mask> containers = new WeakHashMap<>();
  private WeakReference<Object> cast = new WeakReference<>(null);
  private Object icons;
  private Class<?> iconView;
  private String slot;
  private boolean failed, samsungRecording, owned, nativeVisible;
  private final Runnable refresh = this::refresh;

  private RightRecordingIndicator(HookRuntime runtime) {
    rt = runtime;
  }

  static RightRecordingIndicator install(HookRuntime runtime, ClassLoader loader) {
    RightRecordingIndicator bridge = new RightRecordingIndicator(runtime);
    try {
      bridge.install(loader);
    } catch (Throwable error) {
      bridge.fail(error);
    }
    return bridge;
  }

  private void install(ClassLoader loader) throws Exception {
    Class<?> policy =
        Reflect.findClass("com.android.systemui.statusbar.phone.PhoneStatusBarPolicy", loader);
    Class<?> controller =
        Reflect.findClass(
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl", loader);
    Class<?> container =
        Reflect.findClass("com.android.systemui.statusbar.phone.NotificationIconContainer", loader);
    iconView = Reflect.findClass("com.android.systemui.statusbar.StatusBarIconView", loader);
    if (!ViewGroup.class.isAssignableFrom(container) || !View.class.isAssignableFrom(iconView))
      throw new IllegalStateException("Status-bar view types changed");
    controller.getDeclaredMethod("setIconVisibility", String.class, boolean.class);
    container.getDeclaredMethod("resetViewStates");
    container.getDeclaredMethod("shouldForceOverflow", int.class, float.class, int.class);
    // These native callers inline resetViewStates/shouldForceOverflow on this firmware.
    Hooks.deoptimize(container.getDeclaredMethod("updateState"));
    Hooks.deoptimize(container.getDeclaredMethod("calculateIconXTranslations"));
    Hooks.deoptimize(
        container.getDeclaredMethod(
            "onLayout", boolean.class, int.class, int.class, int.class, int.class));
    Hooks.hookAllConstructors(
        policy,
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (failed || p.hasThrowable()) return;
            try {
              icons = Reflect.getObjectField(p.thisObject, "mIconController");
              slot = (String) Reflect.getObjectField(p.thisObject, "mSlotScreenRecord");
              if (!controller.isInstance(icons) || slot == null || slot.isBlank())
                throw new IllegalStateException("Recording slot unavailable");
              queue();
            } catch (Throwable error) {
              fail(error);
            }
          }
        });
    Class<?> castController =
        Reflect.findClass("com.android.systemui.statusbar.phone.SamsungCastIconController", loader);
    Hooks.hookAllConstructors(
        castController,
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!p.hasThrowable()) observe(p.thisObject);
          }
        });
    Hooks.hookAllMethods(
        container,
        "resetViewStates",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            try {
              restoreMask((ViewGroup) p.thisObject);
            } catch (Throwable error) {
              fail(error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (failed || p.hasThrowable()) return;
            try {
              mask((ViewGroup) p.thisObject);
            } catch (Throwable error) {
              fail(error);
            }
          }
        });
    Hooks.hookAllMethods(
        container,
        "shouldForceOverflow",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (failed) return;
            Mask mask = containers.get(p.thisObject);
            if (mask != null)
              p.args[0] = RecordingIndicatorPolicy.visibleIndex((int) p.args[0], mask.indices);
          }
        });
    rt.onOptionsChanged(this::queue);
    Hooks.log("GalaxyCapture: right recording slot hooks ready");
  }

  void observe(Object owner) {
    if (failed) return;
    cast = new WeakReference<>(owner);
    queue();
  }

  private void queue() {
    main.removeCallbacks(refresh);
    main.post(refresh);
  }

  private void refresh() {
    if (failed) return;
    try {
      samsungRecording = false;
      Object owner = cast.get();
      if (owner != null) {
        Object devices =
            Reflect.callMethod(Reflect.getObjectField(owner, "castController"), "getCastDevices");
        if (!(devices instanceof List<?> list))
          throw new IllegalStateException("Cast list changed");
        for (Object device : list) {
          if (!Reflect.getBooleanField(device, "isCasting")) continue;
          Object tag = Reflect.getObjectField(device, "tag");
          if (tag != null
              && "android.media.projection.MediaProjectionInfo".equals(tag.getClass().getName())
              && "com.samsung.android.app.smartcapture"
                  .equals(Reflect.callMethod(tag, "getPackageName"))) samsungRecording = true;
        }
      }
      boolean on =
          rt.options.enabled()
              && rt.guard.allowed()
              && rt.options.recordingIconRight()
              && samsungRecording;
      if (icons != null && slot != null) {
        if (on) {
          if (!owned) nativeVisible = currentVisible();
          Reflect.callMethod(icons, "setIconVisibility", slot, true);
          owned = true;
        } else restoreRight();
      }
      refreshContainers();
    } catch (Throwable error) {
      fail(error);
    }
  }

  private boolean currentVisible() {
    Object list = Reflect.getObjectField(icons, "mStatusBarIconList");
    Object holder = Reflect.callMethod(list, "getIconHolder", 0, slot);
    if (holder == null) throw new IllegalStateException("Native recording icon not initialized");
    Object icon = Reflect.getObjectField(holder, "icon");
    if (icon == null) throw new IllegalStateException("Native recording icon model missing");
    return Reflect.getBooleanField(icon, "visible");
  }

  private void restoreRight() {
    if (!owned) return;
    if (currentVisible()) Reflect.callMethod(icons, "setIconVisibility", slot, nativeVisible);
    owned = false;
  }

  private boolean statusBar(ViewGroup container) {
    if (container.getId() == View.NO_ID) return false;
    return "notificationIcons"
            .equals(container.getResources().getResourceEntryName(container.getId()))
        && !Reflect.getBooleanField(container, "mOnKeyguardStatusBar");
  }

  private boolean rightVisible(View view) {
    if (iconView.isInstance(view)) {
      return Objects.equals(slot, Reflect.getObjectField(view, "mSlot"))
          && Reflect.getObjectField(view, "mNotification") == null
          && !Reflect.getBooleanField(view, "mBlocked")
          && view.isShown()
          && Reflect.getIntField(view, "mVisibleState") == 0;
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (rightVisible(group.getChildAt(i))) return true;
    return false;
  }

  @SuppressWarnings("unchecked")
  private void mask(ViewGroup container) {
    if (!statusBar(container)) return;
    Mask mask = containers.computeIfAbsent(container, key -> new Mask());
    mask.indices = new int[0];
    if (!rt.options.enabled()
        || !rt.guard.allowed()
        || !rt.options.recordingIconRight()
        || !samsungRecording
        || !owned
        || !rightVisible(container.getRootView())) return;
    Object raw = Reflect.getObjectField(container, "mIconStates");
    if (!(raw instanceof Map<?, ?> states))
      throw new IllegalStateException("Icon state map changed");
    List<Integer> hidden = new ArrayList<>();
    for (int i = 0; i < container.getChildCount(); i++) {
      View view = container.getChildAt(i);
      if (!iconView.isInstance(view)) continue;
      Object notification = Reflect.getObjectField(view, "mNotification");
      if (!(notification instanceof StatusBarNotification sbn)) continue;
      if (!RecordingIndicatorPolicy.hideLeft(
          true,
          true,
          true,
          true,
          sbn.getPackageName(),
          sbn.getId(),
          sbn.getNotification().getChannelId(),
          sbn.isOngoing())) continue;
      Object state = states.get(view);
      if (state == null) continue;
      float amount = Reflect.getFloatField(state, "iconAppearAmount");
      if (amount <= 0) continue;
      mask.original.put(state, amount);
      Reflect.setFloatField(state, "iconAppearAmount", 0f);
      Reflect.setBooleanField(state, "hidden", true);
      hidden.add(i);
    }
    mask.indices = hidden.stream().mapToInt(Integer::intValue).toArray();
  }

  private void restoreMask(ViewGroup container) {
    Mask mask = containers.get(container);
    if (mask == null) return;
    for (var entry : new ArrayList<>(mask.original.entrySet())) {
      if (entry.getKey() != null && Reflect.getFloatField(entry.getKey(), "iconAppearAmount") == 0f)
        Reflect.setFloatField(entry.getKey(), "iconAppearAmount", entry.getValue());
    }
    mask.original.clear();
    mask.indices = new int[0];
    // Native resetViewStates clears hidden/alpha immediately after this callback.
  }

  private void refreshContainers() {
    for (ViewGroup container : new ArrayList<>(containers.keySet())) {
      if (container.isAttachedToWindow()) container.requestLayout();
    }
  }

  private void fail(Throwable error) {
    failed = true;
    samsungRecording = false;
    try {
      restoreRight();
    } catch (Throwable ignored) {
    }
    refreshContainers();
    Hooks.log("GalaxyCapture: right recording icon disabled; native indicators retained: " + error);
  }

  private static final class Mask {
    final Map<Object, Float> original = new WeakHashMap<>();
    int[] indices = new int[0];
  }
}
