package dev.ryuya.galaxycapture;

import android.os.Handler;
import dev.ryuya.galaxycapture.hook.*;
import java.util.List;

/** Removes only the cast icon duplicated by known local recorders; recording UI is untouched. */
final class RecordingIndicator {
  static void install(HookRuntime rt, ClassLoader loader) {
    RightRecordingIndicator.install(rt, loader);
    Class<?> controller =
        Reflect.findClass(
            "com.android.systemui.statusbar.phone.SamsungCastIconController", loader);
    var hooks =
        Hooks.hookAllMethods(
            controller,
            "access$updateIconVisibility",
            new HookCallback() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                if (!rt.options.enabled()
                    || (!rt.options.hideDuplicateIndicator() && !rt.options.recordingIconRight())
                    || !rt.guard.allowed()) return;
                try {
                  Object owner = p.args[0];
                  Object cast = Reflect.getObjectField(owner, "castController");
                  List<?> devices = (List<?>) Reflect.callMethod(cast, "getCastDevices");
                  boolean recorder = false, samsungOnly = true;
                  for (Object device : devices) {
                    if (!Reflect.getBooleanField(device, "isCasting")) continue;
                    Object tag = Reflect.getObjectField(device, "tag");
                    if (tag == null
                        || !tag.getClass()
                            .getName()
                            .equals("android.media.projection.MediaProjectionInfo")) return;
                    String pkg = (String) Reflect.callMethod(tag, "getPackageName");
                    if (!pkg.equals("com.samsung.android.app.smartcapture")
                        && !pkg.equals("com.android.systemui")) return;
                    samsungOnly &= pkg.equals("com.samsung.android.app.smartcapture");
                    recorder = true;
                  }
                  if (!recorder || (!rt.options.hideDuplicateIndicator() && !samsungOnly)) return;
                  Handler handler = (Handler) Reflect.getObjectField(owner, "mainHandler");
                  handler.removeCallbacks(
                      (Runnable) Reflect.getObjectField(owner, "removeRunnable"));
                  Object icons = Reflect.getObjectField(owner, "iconController");
                  String slot = (String) Reflect.getObjectField(owner, "slotName");
                  Reflect.callMethod(icons, "setIconVisibility", slot, false);
                  p.setResult(null);
                } catch (Throwable error) {
                  // Keep the native indicator on any failure; never alter projection or
                  // notifications.
                  rt.fail(error);
                }
              }
            });
    if (hooks.isEmpty()) throw new IllegalStateException("Cast indicator callback unavailable");
  }
}
