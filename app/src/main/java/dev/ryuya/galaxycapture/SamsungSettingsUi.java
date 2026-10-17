package dev.ryuya.galaxycapture;

import android.app.Dialog;
import android.content.*;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.Drawable;
import dev.ryuya.galaxycapture.hook.*;
import java.util.*;

/** A native Samsung settings entry opens a separate, categorized custom settings window. */
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
          state.addEntry();
        } catch (Throwable e) { Hooks.log("GalaxyCapture settings unavailable: " + e); }
      }
    });
    Hooks.hookAllMethods(fragment, "onResume", new HookCallback() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
        if (state != null) state.resume();
      }
    });
    Hooks.hookAllMethods(fragment, "onPause", new HookCallback() {
      @Override protected void beforeHookedMethod(MethodHookParam p) {
        State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
        if (state != null) state.pause();
      }
    });
    Hooks.hookAllMethods(fragment, "onDestroy", new HookCallback() {
      @Override protected void beforeHookedMethod(MethodHookParam p) {
        State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
        if (state != null) { state.pause(); state.close(); }
      }
    });
  }

  private static final class State {
    final ClassLoader loader;
    final Object fragment;
    final Context host, strings;
    final Map<String, Object> preferences = new LinkedHashMap<>();
    final Map<String, Object> screens = new LinkedHashMap<>();
    boolean registered, master;
    Object manager, toolbar, list;
    View switchBar;
    Dialog dialog;
    String currentPage = "home";
    final BroadcastReceiver updates = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    State(ClassLoader loader, Object fragment) throws Exception {
      this.loader = loader; this.fragment = fragment;
      host = (Context) Reflect.callMethod(fragment, "getContext");
      strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    }
    void addEntry() {
      if (Reflect.callMethod(fragment, "findPreference", KEY + "entry") != null) return;
      Object group = make("PreferenceCategory", "category", R.string.app_name);
      Object last = Reflect.getObjectField(fragment, "mScreenRecorderFolderPickerPreference");
      if (last == null) last = Reflect.getObjectField(fragment, "mScreenRecorder");
      if (last == null) return;
      Reflect.callMethod(group, "setOrder", ((Number) Reflect.callMethod(last, "getOrder")).intValue() + 1);
      Reflect.callMethod(Reflect.callMethod(fragment, "getPreferenceScreen"), "f", group);
      Object entry = make("Preference", "entry", R.string.custom_settings_entry);
      Reflect.callMethod(entry, "setSummary", strings.getText(R.string.custom_settings_summary));
      click(entry, this::open);
      Reflect.callMethod(group, "f", entry);
    }
    Object make(String type, String key, int title) {
      Object pref = Reflect.newInstance(Reflect.findClass("androidx.preference." + type, loader), host, null);
      Reflect.callMethod(pref, "setKey", KEY + key);
      Reflect.callMethod(pref, "setTitle", strings.getText(title));
      Reflect.callMethod(pref, "setPersistent", false);
      return pref;
    }
    Object screen(String name) {
      Object screen = make("PreferenceScreen", "screen." + name, R.string.custom_settings_entry);
      Reflect.callMethod(screen, "onAttachedToHierarchy", manager);
      screens.put(name, screen);
      return screen;
    }
    void click(Object pref, Runnable action) {
      Class<?> callback = Reflect.findClass("androidx.preference.Preference$OnPreferenceClickListener", loader);
      Object listener = HostCallbacks.create(loader, callback, (proxy, method, args) -> {
        action.run(); return true;
      });
      Reflect.callMethod(pref, "setOnPreferenceClickListener", listener);
    }
    void buildPages() {
      manager = Reflect.newInstance(Reflect.findClass("androidx.preference.PreferenceManager", loader), host);
      Object home = screen("home");
      String[][] groups = {
        {"video", "rotation", "ignore_half_turns", "single_window", "secure_recording"},
        {"audio", "app_audio_only", "speaker_media", "recording_volume", "media_volume", "mic_volume"},
        {"controls", "samsung_app", "countdown", "hide_duplicate_indicator", "hide_recording_controls"},
        {"screenshots", "secure_screenshot", "exclude_capture_controls"}
      };
      int[] titles = {R.string.custom_video, R.string.section_audio, R.string.custom_controls, R.string.section_screenshot};
      int[] summaries = {R.string.custom_video_detail, R.string.custom_audio_detail, R.string.custom_controls_detail, R.string.custom_screenshot_detail};
      Map<String, CaptureSettings.Toggle> toggles = new HashMap<>();
      for (var toggle : CaptureSettings.all()) toggles.put(toggle.key(), toggle);
      Class<?> listener = Reflect.findClass("androidx.preference.Preference$OnPreferenceChangeListener", loader);
      Object callback = HostCallbacks.create(loader, listener, (proxy, method, args) -> {
        String key = ((String) Reflect.callMethod(args[0], "getKey")).substring(KEY.length());
        Bundle update = new Bundle();
        if (args[1] instanceof Boolean value) update.putBoolean(key, value);
        else update.putInt(key, AudioLevels.clamp((int) args[1]));
        return save(update);
      });
      for (int n = 0; n < groups.length; n++) {
        String[] group = groups[n];
        String page = group[0];
        Object navigation = make("Preference", "nav." + page, titles[n]);
        Reflect.callMethod(navigation, "setSummary", strings.getText(summaries[n]));
        click(navigation, () -> showPage(page));
        Reflect.callMethod(home, "f", navigation);
        Object screen = screen(page);
        for (int i = 1; i < group.length; i++) {
          String key = group[i];
          var toggle = toggles.get(key);
          Object pref = toggle == null
              ? make("SeekBarPreference", key, key.equals("media_volume") ? R.string.media_recording_volume : R.string.mic_recording_volume)
              : make("SwitchPreferenceCompat", key, toggle.title());
          if (toggle != null) Reflect.callMethod(pref, "setSummary", strings.getText(toggle.detail()));
          Reflect.callMethod(pref, "setOnPreferenceChangeListener", callback);
          preferences.put(key, pref);
          Reflect.callMethod(screen, "f", pref);
        }
      }
    }
    void open() {
      try {
        if (dialog != null && dialog.isShowing()) return;
        if (screens.isEmpty()) buildPages();
        dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        var root = new LinearLayout(host); root.setOrientation(LinearLayout.VERTICAL);
        TypedValue color = new TypedValue(); host.getTheme().resolveAttribute(android.R.attr.colorBackground, color, true);
        root.setBackgroundColor(color.data);
        toolbar = Reflect.newInstance(Reflect.findClass("androidx.appcompat.widget.Toolbar", loader), host, null);
        Reflect.callMethod(toolbar, "setTitle", strings.getText(R.string.custom_settings_entry));
        Reflect.callMethod(toolbar, "setNavigationIcon", backIcon());
        Reflect.callMethod(toolbar, "setNavigationContentDescription", strings.getText(R.string.custom_back));
        Reflect.callMethod(toolbar, "setNavigationOnClickListener", (View.OnClickListener) v -> back());
        root.addView((View) toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
        switchBar = (View) Reflect.newInstance(Reflect.findClass("androidx.appcompat.widget.SeslSwitchBar", loader), host, null);
        Reflect.callMethod(switchBar, "setSessionDescription", strings.getString(R.string.enabled));
        Class<?> listener = Reflect.findClass("androidx.appcompat.widget.SeslSwitchBar$OnSwitchChangeListener", loader);
        Reflect.callMethod(switchBar, "c", HostCallbacks.create(loader, listener, (proxy, method, args) -> {
          Bundle update = new Bundle(); update.putBoolean("enabled", (boolean) args[1]);
          if (!save(update)) refresh();
          return null;
        }));
        var scope = new TextView(host); scope.setText(strings.getText(R.string.enabled));
        scope.setTextSize(12); scope.setPadding(dp(24), 0, dp(24), dp(4));
        root.addView(scope);
        root.addView(switchBar, new LinearLayout.LayoutParams(-1, -2));
        list = Reflect.newInstance(Reflect.findClass("androidx.recyclerview.widget.RecyclerView", loader), host, null);
        Reflect.callMethod(list, "setLayoutManager", Reflect.newInstance(Reflect.findClass("androidx.recyclerview.widget.LinearLayoutManager", loader), 1));
        root.addView((View) list, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(root);
        dialog.setOnKeyListener((d, key, event) -> {
          if (key != KeyEvent.KEYCODE_BACK) return false;
          if (event.getAction() == KeyEvent.ACTION_UP) back();
          return true;
        });
        dialog.setOnDismissListener(d -> {
          if (screens.get(currentPage) != null) Reflect.callMethod(screens.get(currentPage), "onDetached");
          if (list != null) Reflect.callMethod(list, "setAdapter", (Object) null);
          switchBar = null; toolbar = null; list = null; dialog = null;
        });
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(color.data));
        window.setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
          var bounds = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom); return insets;
        });
        dialog.show();
        window.setLayout(-1, -1); root.requestApplyInsets();
        // Own the dialog's predictive/system back callback without changing the parent Activity.
        window.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        showPage("home"); refresh();
      } catch (Throwable e) {
        Hooks.log("GalaxyCapture custom settings: " + e); close();
      }
    }
    Drawable backIcon() {
      int attr = host.getResources().getIdentifier("homeAsUpIndicator", "attr", host.getPackageName());
      if (attr != 0) {
        var values = host.obtainStyledAttributes(new int[] {attr});
        try { Drawable drawable = values.getDrawable(0); if (drawable != null) return drawable; }
        finally { values.recycle(); }
      }
      return strings.getResources().getDrawable(R.drawable.ic_back, host.getTheme());
    }

    void showPage(String name) {
      if (list == null) return;
      if (screens.get(currentPage) != null) Reflect.callMethod(screens.get(currentPage), "onDetached");
      currentPage = name;
      int title = switch (name) {
        case "video" -> R.string.custom_video;
        case "audio" -> R.string.section_audio;
        case "controls" -> R.string.custom_controls;
        case "screenshots" -> R.string.section_screenshot;
        default -> R.string.custom_settings_entry;
      };
      Reflect.callMethod(toolbar, "setTitle", strings.getText(title));
      Object screen = screens.get(name);
      Reflect.callMethod(screen, "onAttached");
      Reflect.callMethod(list, "setAdapter", Reflect.newInstance(Reflect.findClass("androidx.preference.PreferenceGroupAdapter", loader), screen));
      refresh();
    }
    void back() { if (currentPage.equals("home")) close(); else showPage("home"); }
    void close() { if (dialog != null) dialog.dismiss(); }
    boolean save(Bundle update) {
      try { host.getContentResolver().call(ConfigProvider.URI, "native_settings", null, update); return true; }
      catch (Throwable e) { Hooks.log("GalaxyCapture settings save: " + e); return false; }
    }
    void refresh() {
      try {
        Bundle b = host.getContentResolver().call(ConfigProvider.URI, "config", null, null);
        if (b == null) return;
        master = b.getBoolean("enabled", false);
        if (switchBar != null) Reflect.callMethod(switchBar, "setCheckedInternal", master);
        for (CaptureSettings.Toggle toggle : CaptureSettings.all()) {
          Object pref = preferences.get(toggle.key()); if (pref == null) continue;
          Reflect.callMethod(pref, "setChecked", b.getBoolean(toggle.key(), toggle.defaultValue()));
          Reflect.callMethod(pref, "setEnabled", master);
        }
        for (String key : new String[] {"media_volume", "mic_volume"}) {
          Object pref = preferences.get(key); if (pref == null) continue;
          int value = AudioLevels.clamp(b.getInt(key, 100));
          Reflect.callMethod(pref, "g", value, true); Reflect.callMethod(pref, "setSummary", value + "%");
          Reflect.callMethod(pref, "setEnabled", master && b.getBoolean("recording_volume", false));
        }
      } catch (Throwable e) { Hooks.log("GalaxyCapture settings refresh: " + e); }
    }
    void resume() {
      if (!registered) {
        host.registerReceiver(updates, new IntentFilter(HookRuntime.CONFIG_CHANGED), ConfigProvider.PERMISSION,
            new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        registered = true;
      }
      refresh();
    }
    void pause() { if (registered) host.unregisterReceiver(updates); registered = false; }
    int dp(int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
  }
}
