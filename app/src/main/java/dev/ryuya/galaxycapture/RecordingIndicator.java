package dev.ryuya.galaxycapture;

import android.os.Handler;
import de.robv.android.xposed.*;
import java.util.List;

/** Removes only the cast icon duplicated by known local recorders; recording UI is untouched. */
final class RecordingIndicator {
  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> controller =
        XposedHelpers.findClass(
            "com.android.systemui.statusbar.phone.SamsungCastIconController", loader);
    var hooks =
        XposedBridge.hookAllMethods(
            controller,
            "access$updateIconVisibility",
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam p) {
                if (!rt.options.enabled()
                    || !rt.options.hideDuplicateIndicator()
                    || !rt.guard.allowed()) return;
                try {
                  Object owner = p.args[0];
                  Object cast = XposedHelpers.getObjectField(owner, "castController");
                  List<?> devices = (List<?>) XposedHelpers.callMethod(cast, "getCastDevices");
                  boolean recorder = false;
                  for (Object device : devices) {
                    if (!XposedHelpers.getBooleanField(device, "isCasting")) continue;
                    Object tag = XposedHelpers.getObjectField(device, "tag");
                    if (tag == null
                        || !tag.getClass()
                            .getName()
                            .equals("android.media.projection.MediaProjectionInfo")) return;
                    String pkg = (String) XposedHelpers.callMethod(tag, "getPackageName");
                    if (!pkg.equals("com.samsung.android.app.smartcapture")
                        && !pkg.equals("com.android.systemui")) return;
                    recorder = true;
                  }
                  if (!recorder) return;
                  Handler handler = (Handler) XposedHelpers.getObjectField(owner, "mainHandler");
                  handler.removeCallbacks(
                      (Runnable) XposedHelpers.getObjectField(owner, "removeRunnable"));
                  Object icons = XposedHelpers.getObjectField(owner, "iconController");
                  String slot = (String) XposedHelpers.getObjectField(owner, "slotName");
                  XposedHelpers.callMethod(icons, "setIconVisibility", slot, false);
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
