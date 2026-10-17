package dev.ryuya.galaxycapture;

import android.content.*;
import android.os.*;
import dev.ryuya.galaxycapture.hook.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** Samsung's own SESL settings widgets, backed by the module's single preference store. */
final class SamsungSettingsUi {
  private static final String STATE = "galaxycapture.settings";
  private static final String KEY = "galaxycapture.";
  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> fragment = Reflect.findClass("com.samsung.android.app.settings.SettingsFragment", loader);
    Hooks.hookAllMethods(fragment, "onCreate", new HookCallback() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        if (p.hasThrowable()) return;
        try {
          State state = new State(loader, p.thisObject);
          Reflect.setAdditionalInstanceField(p.thisObject, STATE, state);
          state.add();
        } catch (Throwable e) { Hooks.log("GalaxyCapture settings unavailable: " + e); }
      }
    });
    Hooks.hookAllMethods(fragment, "onResume", new HookCallback() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
        if (state != null) state.resume();
      }
    });
    for (String method : new String[] {"onPause", "onDestroy"})
      Hooks.hookAllMethods(fragment, method, new HookCallback() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
          State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
          if (state != null) state.pause();
        }
      });
  }

  private static final class State {
    final ClassLoader loader;
    final Object fragment;
    final Context host, strings;
    final Map<String, Object> preferences = new LinkedHashMap<>();
    boolean registered;
    final BroadcastReceiver updates = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    State(ClassLoader loader, Object fragment) throws Exception {
      this.loader = loader; this.fragment = fragment;
      host = (Context) Reflect.callMethod(fragment, "getContext");
      strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    }
    void add() {
      Object group = Reflect.getObjectField(fragment, "mScreenRecorder");
      if (group == null || Reflect.callMethod(fragment, "findPreference", KEY + "enabled") != null) return;
      Class<?> listener = Reflect.findClass("androidx.preference.Preference$OnPreferenceChangeListener", loader);
      Object callback = Proxy.newProxyInstance(loader, new Class<?>[] {listener}, (proxy, method, args) -> {
        if (!method.getName().equals("onPreferenceChange")) {
          if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
          if (method.getName().equals("equals")) return proxy == args[0];
          return "GalaxyCapture settings listener";
        }
        String key = ((String) Reflect.callMethod(args[0], "getKey")).substring(KEY.length());
        Bundle update = new Bundle();
        if (args[1] instanceof Boolean value) update.putBoolean(key, value);
        else update.putInt(key, AudioLevels.clamp((int) args[1]));
        try {
          host.getContentResolver().call(ConfigProvider.URI, "native_settings", null, update);
          return true; // Provider notifies both settings surfaces after persisting.
        } catch (Throwable e) { Hooks.log("GalaxyCapture settings save: " + e); return false; }
      });
      for (CaptureSettings.Toggle toggle : CaptureSettings.all()) {
        Object pref = preference("SwitchPreferenceCompat", toggle.key(), toggle.title());
        Reflect.callMethod(pref, "setSummary", strings.getText(toggle.detail()));
        Reflect.callMethod(pref, "setOnPreferenceChangeListener", callback);
        Reflect.callMethod(group, "f", pref);
        if (toggle.key().equals("recording_volume")) {
          for (String key : new String[] {"media_volume", "mic_volume"}) {
            Object level = preference("SeekBarPreference", key,
                key.equals("media_volume") ? R.string.media_recording_volume : R.string.mic_recording_volume);
            Reflect.callMethod(level, "setOnPreferenceChangeListener", callback);
            Reflect.callMethod(group, "f", level);
          }
        }
      }
      refresh();
    }
    Object preference(String type, String key, int title) {
      Object pref = Reflect.newInstance(Reflect.findClass("androidx.preference." + type, loader), host, null);
      Reflect.callMethod(pref, "setKey", KEY + key);
      Reflect.callMethod(pref, "setTitle", strings.getText(title));
      Reflect.callMethod(pref, "setPersistent", false);
      preferences.put(key, pref);
      return pref;
    }
    void refresh() {
      try {
        Bundle b = host.getContentResolver().call(ConfigProvider.URI, "config", null, null);
        if (b == null) return;
        boolean master = b.getBoolean("enabled", false);
        for (CaptureSettings.Toggle toggle : CaptureSettings.all()) {
          Object pref = preferences.get(toggle.key());
          if (pref == null) continue;
          Reflect.callMethod(pref, "setChecked", b.getBoolean(toggle.key(), toggle.defaultValue()));
          Reflect.callMethod(pref, "setEnabled", toggle.key().equals("enabled") || master);
        }
        for (String key : new String[] {"media_volume", "mic_volume"}) {
          Object pref = preferences.get(key);
          if (pref == null) continue;
          int value = AudioLevels.clamp(b.getInt(key, 100));
          Reflect.callMethod(pref, "g", value, true);
          Reflect.callMethod(pref, "setSummary", value + "%");
          Reflect.callMethod(pref, "setEnabled", master && b.getBoolean("recording_volume", false));
        }
      } catch (Throwable e) { Hooks.log("GalaxyCapture settings refresh: " + e); }
    }
    void resume() {
      if (!registered) {
        host.registerReceiver(updates, new IntentFilter(HookRuntime.CONFIG_CHANGED),
            ConfigProvider.PERMISSION, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        registered = true;
      }
      refresh();
    }
    void pause() {
      if (registered) host.unregisterReceiver(updates);
      registered = false;
    }
  }
}
