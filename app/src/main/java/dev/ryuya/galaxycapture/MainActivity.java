package dev.ryuya.galaxycapture;

import android.content.*;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class MainActivity extends AppCompatActivity {
  private TextView status;
  private final java.util.List<LinearLayout> individual = new java.util.ArrayList<>();
  private SettingsTabs pages;
  private boolean syncing;
  private final java.util.Map<String, MaterialSwitch> toggles = new java.util.HashMap<>();
  private final java.util.Map<String, com.google.android.material.slider.Slider> levels =
      new java.util.HashMap<>();
  private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changed =
      (p, key) -> syncSettings();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    var safe = new LinearLayout(this);
    safe.setOrientation(LinearLayout.VERTICAL);
    setContentView(safe);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        safe,
        (v, i) -> {
          var b =
              i.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          v.setPadding(b.left, b.top, b.right, b.bottom);
          return i;
        });
    androidx.core.view.ViewCompat.requestApplyInsets(safe);
    var bar = new MaterialToolbar(this);
    bar.setTitle(R.string.app_name);
    safe.addView(bar, new LinearLayout.LayoutParams(-1, dp(64)));
    var master = new LinearLayout(this);
    master.setOrientation(LinearLayout.VERTICAL);
    master.setPadding(dp(20), 0, dp(20), 0);
    safe.addView(master);
    addMasterSwitch(master);
    pages = new SettingsTabs(this, safe, state);
    LinearLayout common = pages.page(R.string.tab_common);
    LinearLayout galaxy = pages.page(R.string.tab_galaxy);
    LinearLayout operation = pages.page(R.string.tab_status);
    LinearLayout information = pages.page(R.string.tab_about);
    addScope(common, R.string.scope_common_title, R.string.scope_common_detail);
    addScope(galaxy, R.string.scope_galaxy_title, R.string.scope_galaxy_detail);
    LinearLayout commonOptions = new LinearLayout(this);
    commonOptions.setOrientation(LinearLayout.VERTICAL);
    common.addView(commonOptions);
    LinearLayout galaxyOptions = new LinearLayout(this);
    galaxyOptions.setOrientation(LinearLayout.VERTICAL);
    galaxy.addView(galaxyOptions);
    individual.add(commonOptions);
    individual.add(galaxyOptions);
    addHeading(commonOptions, R.string.section_video);
    addSwitch(commonOptions, "rotation", R.string.rotation, R.string.rotation_detail, true);
    addSwitch(
        commonOptions,
        "ignore_half_turns",
        R.string.ignore_half_turns,
        R.string.ignore_half_turns_detail,
        false);
    addSwitch(
        commonOptions,
        "single_window",
        R.string.single_window,
        R.string.single_window_detail,
        false);
    addSwitch(
        commonOptions,
        "secure_recording",
        R.string.secure_recording,
        R.string.secure_recording_detail,
        false);
    addHeading(commonOptions, R.string.section_audio_display);
    addSwitch(
        commonOptions,
        "app_audio_only",
        R.string.app_audio_only,
        R.string.app_audio_only_detail,
        true);
    addSwitch(
        commonOptions,
        "hide_duplicate_indicator",
        R.string.hide_duplicate_indicator,
        R.string.hide_duplicate_indicator_detail,
        true);
    addSwitch(
        galaxyOptions,
        "recording_icon_right",
        R.string.recording_icon_right,
        R.string.recording_icon_right_detail,
        false);
    addHeading(galaxyOptions, R.string.section_recording_start);
    addSwitch(
        galaxyOptions, "samsung_app", R.string.samsung_app, R.string.samsung_app_detail, true);
    addSwitch(galaxyOptions, "countdown", R.string.countdown, R.string.countdown_detail, true);
    addHeading(galaxyOptions, R.string.section_audio);
    addSwitch(
        galaxyOptions,
        "speaker_media",
        R.string.speaker_media,
        R.string.speaker_media_detail,
        true);
    addSwitch(
        galaxyOptions,
        "recording_volume",
        R.string.recording_volume,
        R.string.recording_volume_detail,
        false);
    addVolume(galaxyOptions, "media_volume", R.string.media_recording_volume);
    addVolume(galaxyOptions, "mic_volume", R.string.mic_recording_volume);
    addHeading(galaxyOptions, R.string.section_screenshot);
    addSwitch(
        galaxyOptions,
        "secure_screenshot",
        R.string.secure_screenshot,
        R.string.secure_screenshot_detail,
        false);
    addHeading(galaxyOptions, R.string.section_capture_controls);
    addSwitch(
        galaxyOptions,
        "exclude_capture_controls",
        R.string.exclude_capture_controls,
        R.string.exclude_capture_controls_detail,
        true);
    addSwitch(
        galaxyOptions,
        "hide_recording_controls",
        R.string.hide_recording_controls,
        R.string.hide_recording_controls_detail,
        false);
    updateEnabled();
    status = new com.google.android.material.textview.MaterialTextView(this);
    status.setTextSize(14);
    status.setPadding(dp(16), dp(16), dp(16), dp(16));
    var card = new MaterialCardView(this);
    card.addView(status);
    operation.addView(card);
    var refresh = new MaterialButton(this);
    refresh.setText(R.string.refresh);
    refresh.setOnClickListener(v -> showStatus());
    operation.addView(refresh);
    var reset = new MaterialButton(this);
    reset.setText(R.string.reset_guard);
    reset.setOnClickListener(
        v ->
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_guard)
                .setMessage(R.string.reset_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    android.R.string.ok,
                    (d, w) -> {
                      getSharedPreferences("config", 0)
                          .edit()
                          .putString("reset", java.util.UUID.randomUUID().toString())
                          .apply();
                      notifyConfig();
                      showStatus();
                    })
                .show());
    operation.addView(reset);
    var version = new com.google.android.material.textview.MaterialTextView(this);
    version.setText(getString(R.string.app_version, BuildConfig.VERSION_NAME));
    version.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    information.addView(version);
    var intro = new com.google.android.material.textview.MaterialTextView(this);
    intro.setText(R.string.intro);
    intro.setTextSize(16);
    intro.setPadding(0, dp(16), 0, dp(24));
    information.addView(intro);
    var notice = new MaterialButton(this);
    notice.setText(R.string.licenses);
    notice.setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    information.addView(notice);
    pages.restore();
    showStatus();
  }

  private void addMasterSwitch(LinearLayout parent) {
    var heading = new com.google.android.material.textview.MaterialTextView(this);
    heading.setText(R.string.enabled);
    heading.setPadding(dp(8), 0, 0, dp(8));
    parent.addView(heading);
    var card = new MaterialCardView(this);
    card.setRadius(dp(64));
    card.setStrokeWidth(0);
    card.setCardBackgroundColor(
        com.google.android.material.color.MaterialColors.getColor(
            parent, com.google.android.material.R.attr.colorSurfaceContainerHigh));
    var toggle = new MaterialSwitch(this);
    toggle.setContentDescription(getString(R.string.enabled));
    toggle.setTextSize(20);
    toggle.setMinHeight(dp(64));
    toggle.setPadding(dp(24), dp(8), dp(24), dp(8));
    toggles.put("enabled", toggle);
    toggle.setOnCheckedChangeListener(
        (button, on) -> {
          updateMasterLabel(toggle, on);
          if (syncing) return;
          getSharedPreferences("config", 0).edit().putBoolean("enabled", on).apply();
          notifyConfig();
          updateEnabled();
        });
    toggle.setChecked(getSharedPreferences("config", 0).getBoolean("enabled", false));
    updateMasterLabel(toggle, toggle.isChecked());
    card.addView(toggle, new android.view.ViewGroup.LayoutParams(-1, -2));
    parent.addView(card);
    var detail = new com.google.android.material.textview.MaterialTextView(this);
    detail.setText(R.string.master_scope_detail);
    detail.setTextSize(12);
    detail.setPadding(dp(8), dp(8), dp(8), dp(8));
    parent.addView(detail);
  }

  private void updateMasterLabel(MaterialSwitch toggle, boolean on) {
    toggle.setText(on ? R.string.master_on : R.string.master_off);
    toggle.setTextColor(
        com.google.android.material.color.MaterialColors.getColor(
            toggle,
            on
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurface));
  }

  private void addScope(LinearLayout parent, int title, int detail) {
    var card = new MaterialCardView(this);
    card.setCardBackgroundColor(
        com.google.android.material.color.MaterialColors.getColor(
            parent, com.google.android.material.R.attr.colorSurfaceContainer));
    var body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(16), dp(16), dp(16), dp(16));
    var label = new com.google.android.material.textview.MaterialTextView(this);
    label.setText(title);
    label.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    androidx.core.view.ViewCompat.setAccessibilityHeading(label, true);
    body.addView(label);
    var description = new com.google.android.material.textview.MaterialTextView(this);
    description.setText(detail);
    description.setPadding(0, dp(8), 0, 0);
    body.addView(description);
    card.addView(body);
    var position = new LinearLayout.LayoutParams(-1, -2);
    position.bottomMargin = dp(20);
    parent.addView(card, position);
  }

  private void addHeading(LinearLayout parent, int title) {
    var label = new com.google.android.material.textview.MaterialTextView(this);
    label.setText(title);
    label.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
    label.setPadding(dp(4), dp(4), 0, dp(12));
    androidx.core.view.ViewCompat.setAccessibilityHeading(label, true);
    parent.addView(label);
  }

  private void addSwitch(LinearLayout parent, String key, int label, int detail, boolean def) {
    var card = new MaterialCardView(this);
    var box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(12), dp(16), dp(16));
    card.addView(box);
    var toggle = new MaterialSwitch(this);
    toggle.setText(label);
    toggle.setTextSize(17);
    toggle.setChecked(getSharedPreferences("config", 0).getBoolean(key, def));
    toggles.put(key, toggle);
    toggle.setOnCheckedChangeListener(
        (b, on) -> {
          if (syncing) return;
          getSharedPreferences("config", 0).edit().putBoolean(key, on).apply();
          notifyConfig();
          if (key.equals("enabled") || key.equals("recording_volume")) updateEnabled();
        });
    box.addView(toggle);
    var desc = new com.google.android.material.textview.MaterialTextView(this);
    desc.setText(detail);
    box.addView(desc);
    var lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(16);
    parent.addView(card, lp);
  }

  private void addVolume(LinearLayout parent, String key, int title) {
    var card = new MaterialCardView(this);
    var box = new LinearLayout(this);
    box.setTag("recording-volume");
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(12), dp(16), dp(12));
    var label = new com.google.android.material.textview.MaterialTextView(this);
    int current = AudioLevels.clamp(getSharedPreferences("config", 0).getInt(key, 100));
    label.setText(getString(title) + ": " + current + "%");
    box.addView(label);
    var slider = new com.google.android.material.slider.Slider(this);
    levels.put(key, slider);
    slider.setContentDescription(getString(title));
    slider.setValueFrom(0);
    slider.setValueTo(100);
    slider.setStepSize(1);
    slider.setValue(current);
    var pending = new Handler(Looper.getMainLooper());
    Runnable notify = this::notifyConfig;
    slider.addOnChangeListener(
        (v, value, user) -> {
          label.setText(getString(title) + ": " + Math.round(value) + "%");
          if (user && !syncing) {
            getSharedPreferences("config", 0).edit().putInt(key, Math.round(value)).apply();
            pending.removeCallbacks(notify);
            pending.postDelayed(notify, 200);
          }
        });
    box.addView(slider);
    card.addView(box);
    var lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(16);
    parent.addView(card, lp);
  }

  private void updateEnabled() {
    boolean enabled = getSharedPreferences("config", 0).getBoolean("enabled", false);
    for (LinearLayout group : individual) {
      setChildrenEnabled(group, enabled);
      group.setAlpha(enabled ? 1f : .38f);
      updateVolumeEnabled(
          group,
          enabled && getSharedPreferences("config", 0).getBoolean("recording_volume", false));
    }
  }

  private void updateVolumeEnabled(android.view.View view, boolean enabled) {
    if ("recording-volume".equals(view.getTag())) {
      setChildrenEnabled(view, enabled);
      view.setAlpha(enabled ? 1f : .38f);
    } else if (view instanceof android.view.ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) updateVolumeEnabled(g.getChildAt(i), enabled);
  }

  private void setChildrenEnabled(android.view.View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof android.view.ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) setChildrenEnabled(g.getChildAt(i), enabled);
  }

  private void notifyConfig() {
    Startup.notifyHosts(this);
  }

  private void showStatus() {
    var p = getSharedPreferences("status", 0);
    StringBuilder s = new StringBuilder(getString(R.string.status));
    for (String host : new String[] {"framework", "systemui", "samsung"})
      s.append("\n\n")
          .append(host)
          .append(": ")
          .append(p.getString(host, getString(R.string.not_loaded)));
    status.setText(s);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (pages != null) pages.save(out);
    super.onSaveInstanceState(out);
  }

  private void syncSettings() {
    syncing = true;
    try {
      var prefs = getSharedPreferences("config", 0);
      for (CaptureSettings.Toggle setting : CaptureSettings.all()) {
        MaterialSwitch toggle = toggles.get(setting.key());
        if (toggle != null)
          toggle.setChecked(prefs.getBoolean(setting.key(), setting.defaultValue()));
      }
      for (var entry : levels.entrySet())
        entry.getValue().setValue(AudioLevels.clamp(prefs.getInt(entry.getKey(), 100)));
      updateEnabled();
    } finally {
      syncing = false;
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    getSharedPreferences("config", 0).registerOnSharedPreferenceChangeListener(changed);
    syncSettings();
  }

  @Override
  protected void onStop() {
    getSharedPreferences("config", 0).unregisterOnSharedPreferenceChangeListener(changed);
    super.onStop();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (status != null) showStatus();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
