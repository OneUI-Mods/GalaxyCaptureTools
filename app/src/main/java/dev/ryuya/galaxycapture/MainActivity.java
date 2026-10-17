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
  private LinearLayout individual;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    var column = new LinearLayout(this);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(dp(20), 0, dp(20), dp(28));
    var scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(column);
    setContentView(scroll);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, i) -> {
          var b =
              i.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          v.setPadding(b.left, b.top, b.right, b.bottom);
          return i;
        });
    var bar = new MaterialToolbar(this);
    bar.setTitle(R.string.app_name);
    column.addView(bar);
    var intro = new com.google.android.material.textview.MaterialTextView(this);
    intro.setText(R.string.intro);
    intro.setTextSize(16);
    intro.setPadding(0, dp(12), 0, dp(24));
    column.addView(intro);
    addSwitch(column, "enabled", R.string.enabled, R.string.enabled_detail, false);
    individual = new LinearLayout(this);
    individual.setOrientation(LinearLayout.VERTICAL);
    column.addView(individual);
    var heading = new com.google.android.material.textview.MaterialTextView(this);
    heading.setText(R.string.individual_settings);
    heading.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    heading.setPadding(dp(4), 0, 0, dp(12));
    individual.addView(heading);
    addSwitch(individual, "rotation", R.string.rotation, R.string.rotation_detail, true);
    addSwitch(
        individual,
        "ignore_half_turns",
        R.string.ignore_half_turns,
        R.string.ignore_half_turns_detail,
        false);
    addSwitch(individual, "samsung_app", R.string.samsung_app, R.string.samsung_app_detail, true);
    addSwitch(
        individual, "single_window", R.string.single_window, R.string.single_window_detail, false);
    addSwitch(
        individual,
        "app_audio_only",
        R.string.app_audio_only,
        R.string.app_audio_only_detail,
        true);
    addSwitch(
        individual,
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
    column.addView(card);
    var refresh = new MaterialButton(this);
    refresh.setText(R.string.refresh);
    refresh.setOnClickListener(v -> showStatus());
    column.addView(refresh);
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
    column.addView(reset);
    var notice = new MaterialButton(this);
    notice.setText(R.string.licenses);
    notice.setOnClickListener(v -> showLicenses());
    column.addView(notice);
    showStatus();
  }

  private void showLicenses() {
    try {
      StringBuilder text = new StringBuilder();
      for (String name :
          new String[] {
            "PROJECT-MIT.txt", "THIRD-PARTY-NOTICES.txt", "XPOSED-NOTICE.txt", "APACHE-2.0.txt"
          })
        try (var in = getAssets().open("legal/" + name)) {
          text.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
              .append("\n\n");
        }
      var body = new com.google.android.material.textview.MaterialTextView(this);
      body.setText(text);
      body.setTextIsSelectable(true);
      body.setPadding(dp(20), dp(12), dp(20), dp(12));
      var scroll = new androidx.core.widget.NestedScrollView(this);
      scroll.addView(body);
      new MaterialAlertDialogBuilder(this)
          .setTitle(R.string.licenses)
          .setView(scroll)
          .setPositiveButton(android.R.string.ok, null)
          .show();
    } catch (java.io.IOException error) {
      new MaterialAlertDialogBuilder(this)
          .setMessage(R.string.license_detail)
          .setPositiveButton(android.R.string.ok, null)
          .show();
    }
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
    if (individual == null) return;
    boolean enabled = getSharedPreferences("config", 0).getBoolean("enabled", false);
    setChildrenEnabled(individual, enabled);
    individual.setAlpha(enabled ? 1f : .38f);
  }

  private void setChildrenEnabled(android.view.View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof android.view.ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) setChildrenEnabled(g.getChildAt(i), enabled);
  }

  private void notifyConfig() {
    for (String pkg :
        new String[] {"android", "com.android.systemui", "com.samsung.android.app.smartcapture"})
      sendBroadcast(new Intent(HookRuntime.CONFIG_CHANGED).setPackage(pkg));
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
  protected void onResume() {
    super.onResume();
    if (status != null) showStatus();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
