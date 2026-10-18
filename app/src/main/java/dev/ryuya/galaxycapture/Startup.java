package dev.ryuya.galaxycapture;

import android.content.*;
import android.os.UserManager;

/** CE settings stay untouched until unlock; reboot never overrides OFF or crash protection. */
final class Startup {
  static void restore(Context context) {
    if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
    context.getSharedPreferences("config", Context.MODE_PRIVATE); // Start the settings process.
    notifyHosts(context);
  }

  static void notifyHosts(Context context) {
    for (String pkg :
        new String[] {"android", "com.android.systemui", "com.samsung.android.app.smartcapture"})
      context.sendBroadcast(new Intent(HookRuntime.CONFIG_CHANGED).setPackage(pkg));
  }
}
