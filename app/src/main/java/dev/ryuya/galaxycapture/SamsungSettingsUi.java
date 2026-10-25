package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.LinearLayout;
import dev.ryuya.galaxycapture.hook.*;
import java.util.*;

/** Custom pages reuse Samsung's preference surface, floating toolbar, insets and rounding. */
final class SamsungSettingsUi {
  private static final String STATE = "galaxycapture.settings";
  private static final String KEY = "galaxycapture.";
  private static final String ACTIVITY_STATE = STATE + ".activity";

  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> fragment =
        Reflect.findClass("com.samsung.android.app.settings.SettingsFragment", loader);
    Hooks.hookAllMethods(
        Reflect.findClass("androidx.preference.Preference", loader),
        "onBindViewHolder",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
            if (state != null && state.active) state.bindMaster(p.args[0]);
          }
        });
    Class<?> activity =
        Reflect.findClass("com.samsung.android.app.settings.SettingsActivity", loader);
    Hooks.hookAllMethods(
        activity,
        "onOptionsItemSelected",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, ACTIVITY_STATE);
            if (state != null
                && state.active
                && ((MenuItem) p.args[0]).getItemId() == android.R.id.home) {
              state.back();
              p.setResult(true);
            }
          }
        });
    Hooks.hookAllMethods(
        fragment,
        "onCreate",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (p.hasThrowable()) return;
            try {
              State state = new State(loader, p.thisObject);
              Reflect.setAdditionalInstanceField(p.thisObject, STATE, state);
              state.addEntry();
            } catch (Throwable e) {
              Hooks.log("GalaxyCapture settings unavailable: " + e);
            }
          }
        });
    Hooks.hookAllMethods(
        fragment,
        "onConfigurationChanged",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
            if (state != null && state.active) {
              state.switchBar = null;
              state.switchRow = null;
              Object adapter = Reflect.callMethod(state.list, "getAdapter");
              if (adapter != null) Reflect.callMethod(adapter, "notifyDataSetChanged");
            }
          }
        });
    Hooks.hookAllMethods(
        fragment,
        "onResume",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
            if (state != null) state.resume();
          }
        });
    Hooks.hookAllMethods(
        fragment,
        "onPause",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
            if (state != null) state.pause();
          }
        });
    Hooks.hookAllMethods(
        fragment,
        "onDestroy",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = (State) Reflect.getAdditionalInstanceField(p.thisObject, STATE);
            if (state != null) {
              state.pause();
              state.destroy();
            }
          }
        });
  }

  private static final class State {
    final ClassLoader loader;
    final Object fragment;
    final Context host, strings;
    final Map<String, Object> preferences = new LinkedHashMap<>();
    final Map<String, Object> screens = new LinkedHashMap<>();
    boolean registered, master, active;
    Object manager, originalScreen;
    View list, switchBar, switchRow;
    CharSequence originalTitle;
    Parcelable originalListState;
    final android.window.OnBackInvokedCallback backCallback = this::back;
    String currentPage = "home";
    final BroadcastReceiver updates =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context c, Intent i) {
            refresh();
          }
        };

    State(ClassLoader loader, Object fragment) throws Exception {
      this.loader = loader;
      this.fragment = fragment;
      host = (Context) Reflect.callMethod(fragment, "getContext");
      strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    }

    void addEntry() {
      if (Reflect.callMethod(fragment, "findPreference", KEY + "entry") != null) return;
      Object group = make("PreferenceCategory", "category", R.string.app_name);
      Object last = Reflect.getObjectField(fragment, "mScreenRecorderFolderPickerPreference");
      if (last == null) last = Reflect.getObjectField(fragment, "mScreenRecorder");
      if (last == null) return;
      Reflect.callMethod(
          group, "setOrder", ((Number) Reflect.callMethod(last, "getOrder")).intValue() + 1);
      Reflect.callMethod(Reflect.callMethod(fragment, "getPreferenceScreen"), "f", group);
      Object entry = make("Preference", "entry", R.string.custom_settings_entry);
      Reflect.callMethod(entry, "setSummary", strings.getText(R.string.custom_settings_summary));
      click(entry, this::open);
      Reflect.callMethod(group, "f", entry);
    }

    Object make(String type, String key, int title) {
      Object pref =
          Reflect.newInstance(Reflect.findClass("androidx.preference." + type, loader), host, null);
      Reflect.callMethod(pref, "setKey", KEY + key);
      Reflect.callMethod(pref, "setTitle", strings.getText(title));
      Reflect.callMethod(pref, "setPersistent", false);
      return pref;
    }

    Object screen(String name) {
      Object screen = make("PreferenceScreen", "screen." + name, R.string.custom_settings_entry);
      Reflect.callMethod(screen, "onAttachedToHierarchy", manager);
      screens.put(name, screen);
      Object master = make("Preference", "master." + name, R.string.enabled);
      Reflect.callMethod(
          master,
          "setLayoutResource",
          resource("layout", "settings_navigation_height_empty_preference"));
      Reflect.callMethod(master, "setSelectable", false);
      Reflect.setAdditionalInstanceField(master, STATE, this);
      Reflect.callMethod(screen, "f", master);
      Object group = make("PreferenceCategory", "group." + name, R.string.custom_settings_entry);
      Reflect.callMethod(group, "setTitle", "");
      Reflect.callMethod(screen, "f", group);
      return group;
    }

    void click(Object pref, Runnable action) {
      Class<?> callback =
          Reflect.findClass("androidx.preference.Preference$OnPreferenceClickListener", loader);
      Object listener =
          HostCallbacks.create(
              loader,
              callback,
              (proxy, method, args) -> {
                action.run();
                return true;
              });
      Reflect.callMethod(pref, "setOnPreferenceClickListener", listener);
    }

    void buildPages() {
      manager = Reflect.callMethod(fragment, "getPreferenceManager");
      Object home = screen("home");
      String[][] groups = {
        {"video", "rotation", "ignore_half_turns", "single_window", "secure_recording"},
        {
          "audio",
          "app_audio_only",
          "include_call_audio",
          "speaker_media",
          "recording_volume",
          "media_volume",
          "mic_volume"
        },
        {
          "controls",
          "samsung_app",
          "countdown",
          "hide_duplicate_indicator",
          "recording_icon_right",
          "hide_recording_controls"
        },
        {"screenshots", "secure_screenshot", "exclude_capture_controls"}
      };
      int[] titles = {
        R.string.custom_video,
        R.string.section_audio,
        R.string.custom_controls,
        R.string.section_screenshot
      };
      int[] summaries = {
        R.string.custom_video_detail,
        R.string.custom_audio_detail,
        R.string.custom_controls_detail,
        R.string.custom_screenshot_detail
      };
      Map<String, CaptureSettings.Toggle> toggles = new HashMap<>();
      for (var toggle : CaptureSettings.all()) toggles.put(toggle.key(), toggle);
      Class<?> listener =
          Reflect.findClass("androidx.preference.Preference$OnPreferenceChangeListener", loader);
      Object callback =
          HostCallbacks.create(
              loader,
              listener,
              (proxy, method, args) -> {
                String key =
                    ((String) Reflect.callMethod(args[0], "getKey")).substring(KEY.length());
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
          Object pref =
              toggle == null
                  ? make(
                      "SeekBarPreference",
                      key,
                      key.equals("media_volume")
                          ? R.string.media_recording_volume
                          : R.string.mic_recording_volume)
                  : make("SwitchPreferenceCompat", key, toggle.title());
          if (toggle != null)
            Reflect.callMethod(pref, "setSummary", strings.getText(toggle.detail()));
          Reflect.callMethod(pref, "setOnPreferenceChangeListener", callback);
          preferences.put(key, pref);
          Reflect.callMethod(screen, "f", pref);
        }
      }
    }

    int resource(String type, String name) {
      int id = host.getResources().getIdentifier(name, type, host.getPackageName());
      if (id == 0)
        throw new IllegalStateException("Samsung resource unavailable: " + type + "/" + name);
      return id;
    }

    void open() {
      if (active) return;
      try {
        if (screens.isEmpty()) buildPages();
        Activity activity = (Activity) host;
        originalScreen = Reflect.callMethod(fragment, "getPreferenceScreen");
        originalTitle = activity.getTitle();
        list = (View) Reflect.callMethod(fragment, "getListView");
        originalListState =
            (Parcelable)
                Reflect.callMethod(
                    Reflect.callMethod(list, "getLayoutManager"), "onSaveInstanceState");
        active = true;
        Reflect.setAdditionalInstanceField(activity, ACTIVITY_STATE, this);
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(1, backCallback);
        showPage("home");
      } catch (Throwable e) {
        Hooks.log("GalaxyCapture custom settings: " + e);
        close();
      }
    }

    void bindMaster(Object holder) {
      try {
        if (switchRow == null) {
          View nativeSubPage =
              ((Activity) host)
                  .getLayoutInflater()
                  .inflate(resource("layout", "settings_sub_layout"), null);
          switchBar = nativeSubPage.findViewById(resource("id", "switch_bar"));
          if (switchBar == null) throw new IllegalStateException("Samsung switch bar unavailable");
          switchRow = (View) switchBar.getParent();
          ((ViewGroup) switchRow.getParent()).removeView(switchRow);
          Reflect.callMethod(
              switchBar, "setSessionDescription", strings.getString(R.string.enabled));
          Class<?> listener =
              Reflect.findClass(
                  "androidx.appcompat.widget.SeslSwitchBar$OnSwitchChangeListener", loader);
          Reflect.callMethod(
              switchBar,
              "c",
              HostCallbacks.create(
                  loader,
                  listener,
                  (proxy, method, args) -> {
                    Bundle update = new Bundle();
                    update.putBoolean("enabled", (boolean) args[1]);
                    if (!save(update)) refresh();
                    return null;
                  }));
        }
        ViewGroup item = (ViewGroup) Reflect.getObjectField(holder, "itemView");
        if (switchRow.getParent() != item) {
          if (switchRow.getParent() != null)
            ((ViewGroup) switchRow.getParent()).removeView(switchRow);
          item.removeAllViews();
          item.addView(switchRow, new LinearLayout.LayoutParams(-1, -2));
        }
        Reflect.callMethod(switchBar, "setCheckedInternal", master);
      } catch (Throwable e) {
        Hooks.log("GalaxyCapture switch bar: " + e);
      }
    }

    void title(CharSequence title) {
      Activity activity = (Activity) host;
      activity.setTitle(title);
      Object actionBar = Reflect.callMethod(activity, "getSupportActionBar");
      if (actionBar != null) Reflect.callMethod(actionBar, "p", title);
      View collapsing = activity.findViewById(resource("id", "collapsing_toolbar"));
      if (collapsing != null) Reflect.callMethod(collapsing, "setTitle", title);
      View toolbar = activity.findViewById(resource("id", "toolbar"));
      if (toolbar != null) Reflect.callMethod(toolbar, "setTitle", title);
      View appBar = activity.findViewById(resource("id", "app_bar"));
      if (appBar != null) Reflect.callMethod(appBar, "setExpanded", true);
    }

    void showPage(String name) {
      if (!active) return;
      currentPage = name;
      int title =
          switch (name) {
            case "video" -> R.string.custom_video;
            case "audio" -> R.string.section_audio;
            case "controls" -> R.string.custom_controls;
            case "screenshots" -> R.string.section_screenshot;
            default -> R.string.custom_settings_entry;
          };
      title(strings.getText(title));
      Reflect.callMethod(fragment, "setPreferenceScreen", screens.get(name));
      // The original list retains Samsung's divider, rounded corners, horizontal fill,
      // floating-toolbar linkage, scroll behaviour and accessibility semantics.
      list.post(() -> Reflect.callMethod(list, "scrollToPosition", 0));
      refresh();
    }

    void back() {
      if (currentPage.equals("home")) close();
      else showPage("home");
    }

    void close() {
      if (active) {
        active = false;
        ((Activity) host)
            .getOnBackInvokedDispatcher()
            .unregisterOnBackInvokedCallback(backCallback);
        Reflect.setAdditionalInstanceField(host, ACTIVITY_STATE, null);
        Reflect.callMethod(fragment, "setPreferenceScreen", originalScreen);
        title(originalTitle);
        Reflect.callMethod(
            ((Activity) host).findViewById(resource("id", "app_bar")), "setExpanded", false);
        View nativeList = list;
        Parcelable savedPosition = originalListState;
        nativeList.post(
            () ->
                Reflect.callMethod(
                    Reflect.callMethod(nativeList, "getLayoutManager"),
                    "onRestoreInstanceState",
                    savedPosition));
      }
      switchBar = null;
      switchRow = null;
      list = null;
    }

    void destroy() {
      if (active)
        ((Activity) host)
            .getOnBackInvokedDispatcher()
            .unregisterOnBackInvokedCallback(backCallback);
      active = false;
      Reflect.setAdditionalInstanceField(host, ACTIVITY_STATE, null);
      // The fragment view is already destroyed here: never schedule a new preference bind.
      switchBar = null;
      switchRow = null;
      list = null;
    }

    boolean save(Bundle update) {
      try {
        host.getContentResolver().call(ConfigProvider.URI, "native_settings", null, update);
        return true;
      } catch (Throwable e) {
        Hooks.log("GalaxyCapture settings save: " + e);
        return false;
      }
    }

    void refresh() {
      try {
        Bundle b = host.getContentResolver().call(ConfigProvider.URI, "config", null, null);
        if (b == null) return;
        master = b.getBoolean("enabled", false);
        if (switchBar != null) Reflect.callMethod(switchBar, "setCheckedInternal", master);
        for (CaptureSettings.Toggle toggle : CaptureSettings.all()) {
          Object pref = preferences.get(toggle.key());
          if (pref == null) continue;
          Reflect.callMethod(pref, "setChecked", b.getBoolean(toggle.key(), toggle.defaultValue()));
          Reflect.callMethod(pref, "setEnabled", master);
        }
        for (String key : new String[] {"media_volume", "mic_volume"}) {
          Object pref = preferences.get(key);
          if (pref == null) continue;
          int value = AudioLevels.clamp(b.getInt(key, 100));
          Reflect.callMethod(pref, "g", value, true);
          Reflect.callMethod(pref, "setSummary", value + "%");
          Reflect.callMethod(pref, "setEnabled", master && b.getBoolean("recording_volume", false));
        }
      } catch (Throwable e) {
        Hooks.log("GalaxyCapture settings refresh: " + e);
      }
    }

    void resume() {
      if (!registered) {
        host.registerReceiver(
            updates,
            new IntentFilter(HookRuntime.CONFIG_CHANGED),
            ConfigProvider.PERMISSION,
            new Handler(Looper.getMainLooper()),
            Context.RECEIVER_EXPORTED);
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
