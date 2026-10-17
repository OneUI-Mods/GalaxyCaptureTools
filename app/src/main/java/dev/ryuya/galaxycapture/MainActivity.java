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
    pages = new SettingsTabs(this, safe, state);
    LinearLayout recording = pages.page(R.string.tab_recording);
    LinearLayout audioDisplay = pages.page(R.string.tab_audio_display);
    LinearLayout operation = pages.page(R.string.tab_status);
    LinearLayout information = pages.page(R.string.tab_about);
    addSwitch(recording, "enabled", R.string.enabled, R.string.enabled_detail, false);
    LinearLayout recordingOptions = new LinearLayout(this);
    recordingOptions.setOrientation(LinearLayout.VERTICAL);
    recording.addView(recordingOptions);
    LinearLayout audioOptions = new LinearLayout(this);
    audioOptions.setOrientation(LinearLayout.VERTICAL);
    audioDisplay.addView(audioOptions);
    individual.add(recordingOptions);
    individual.add(audioOptions);
    var heading = new com.google.android.material.textview.MaterialTextView(this);
    heading.setText(R.string.individual_settings);
    heading.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    heading.setPadding(dp(4), 0, 0, dp(12));
    recordingOptions.addView(heading);
    addSwitch(recordingOptions, "rotation", R.string.rotation, R.string.rotation_detail, true);
    addSwitch(
        recordingOptions,
        "ignore_half_turns",
        R.string.ignore_half_turns,
        R.string.ignore_half_turns_detail,
        false);
    addSwitch(
        recordingOptions, "samsung_app", R.string.samsung_app, R.string.samsung_app_detail, true);
    addSwitch(
        recordingOptions,
        "single_window",
        R.string.single_window,
        R.string.single_window_detail,
        false);
    addSwitch(
        audioOptions,
        "app_audio_only",
        R.string.app_audio_only,
        R.string.app_audio_only_detail,
        true);
    addSwitch(
        audioOptions,
        "mix_app_audio_with_mic",
        R.string.mix_app_audio_with_mic,
        R.string.mix_app_audio_with_mic_detail,
        true);
    addSwitch(
        audioOptions,
        "hide_duplicate_indicator",
        R.string.hide_duplicate_indicator,
        R.string.hide_duplicate_indicator_detail,
        true);
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
    toggle.setOnCheckedChangeListener(
        (b, on) -> {
          getSharedPreferences("config", 0).edit().putBoolean(key, on).apply();
          notifyConfig();
          if (key.equals("enabled")) updateEnabled();
        });
    box.addView(toggle);
    var desc = new com.google.android.material.textview.MaterialTextView(this);
    desc.setText(detail);
    box.addView(desc);
    var lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(16);
    parent.addView(card, lp);
  }

  private void updateEnabled() {
    boolean enabled = getSharedPreferences("config", 0).getBoolean("enabled", false);
    for (LinearLayout group : individual) {
      setChildrenEnabled(group, enabled);
      group.setAlpha(enabled ? 1f : .38f);
    }
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

  @Override
  protected void onResume() {
    super.onResume();
    if (status != null) showStatus();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
