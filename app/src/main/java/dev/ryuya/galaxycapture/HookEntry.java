package dev.ryuya.galaxycapture;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import dev.ryuya.galaxycapture.hook.*;
import io.github.libxposed.api.XposedModule;

public final class HookEntry extends XposedModule {
  @Override
  public void onModuleLoaded(ModuleLoadedParam p) {
    Hooks.attach(this);
  }
  @Override
  public void onPackageReady(PackageReadyParam p) {
    if (p.isFirstPackage())
      loadPackage(p.getPackageName(), p.getClassLoader(), false);
  }
  @Override
  public void onSystemServerStarting(SystemServerStartingParam p) {
    loadPackage("android", p.getClassLoader(), true);
  }

  private void loadPackage(String packageName, ClassLoader classLoader, boolean systemServer) {
    if (Build.VERSION.SDK_INT != 36 || !Build.MANUFACTURER.equalsIgnoreCase("samsung")
        || !Build.DISPLAY.equals("BP4A.251205.006.S948QOPS1AZHL"))
      return;
    try {
      if (systemServer) {
        FrameworkHooks.install(classLoader);
        Hooks.hookAllMethods(Reflect.findClass("com.android.server.SystemServer", classLoader),
            "startOtherServices", new HookCallback() {
              @Override
              protected void beforeHookedMethod(MethodHookParam param) {
                if (FrameworkHooks.runtime != null)
                  return;
                try {
                  Context c = (Context) Reflect.getObjectField(param.thisObject, "mSystemContext");
                  FrameworkHooks.runtime = new HookRuntime(c, "framework");
                  CaptureTargets.install(FrameworkHooks.runtime, classLoader);
                } catch (Throwable e) {
                  Hooks.log("GalaxyCapture framework unavailable: " + e);
                }
              }
            });
      } else if (packageName.equals("com.android.systemui")
          || packageName.equals("com.samsung.android.app.smartcapture")) {
        boolean system = packageName.equals("com.android.systemui");
        Reflect.findAndHookMethod(Application.class, "attach", Context.class, new HookCallback() {
          private boolean loaded;

          @Override
          protected void afterHookedMethod(MethodHookParam param) {
            if (loaded)
              return;
            loaded = true;
            HookRuntime runtime =
                new HookRuntime((Context) param.args[0], system ? "systemui" : "samsung");
            try {
              if (system)
                SamsungSelection.installSystemUi(runtime, classLoader);
              else
                SamsungSelection.installRecorder(runtime, classLoader);
            } catch (Throwable e) {
              runtime.fail(e);
            }
          }
        });
      }
    } catch (Throwable e) {
      Hooks.log("GalaxyCapture hook initialization stopped: " + e);
    }
  }
}
