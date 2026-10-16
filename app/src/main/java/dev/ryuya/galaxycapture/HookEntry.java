package dev.ryuya.galaxycapture;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
  @Override
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
    if (Build.VERSION.SDK_INT != 36
        || !Build.MANUFACTURER.equalsIgnoreCase("samsung")
        || !Build.DISPLAY.equals("BP4A.251205.006.S948QOPS1AZHL")) return;
    try {
      if (p.packageName.equals("android") && p.processName.equals("android")) {
        FrameworkHooks.install(p.classLoader);
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass("com.android.server.SystemServer", p.classLoader),
            "startOtherServices",
            new XC_MethodHook() {
              @Override
              protected void beforeHookedMethod(MethodHookParam param) {
                if (FrameworkHooks.runtime != null) return;
                try {
                  Context c =
                      (Context) XposedHelpers.getObjectField(param.thisObject, "mSystemContext");
                  FrameworkHooks.runtime = new HookRuntime(c, "framework");
                  CaptureTargets.install(FrameworkHooks.runtime, p.classLoader);
                } catch (Throwable e) {
                  XposedBridge.log("GalaxyCapture framework unavailable: " + e);
                }
              }
            });
      } else if (p.packageName.equals("com.android.systemui")
          || p.packageName.equals("com.samsung.android.app.smartcapture")) {
        boolean system = p.packageName.equals("com.android.systemui");
        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
              private boolean loaded;

              @Override
              protected void afterHookedMethod(MethodHookParam param) {
                if (loaded) return;
                loaded = true;
                HookRuntime runtime =
                    new HookRuntime((Context) param.args[0], system ? "systemui" : "samsung");
                try {
                  if (system) SamsungSelection.installSystemUi(runtime, p.classLoader);
                  else SamsungSelection.installRecorder(runtime, p.classLoader);
                } catch (Throwable e) {
                  runtime.fail(e);
                }
              }
            });
      }
    } catch (Throwable e) {
      XposedBridge.log("GalaxyCapture hook initialization stopped: " + e);
    }
  }
}
