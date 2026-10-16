package dev.ryuya.galaxycapture;

import android.app.Dialog;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.button.*;
import com.google.android.material.textfield.*;
import com.google.android.material.textview.MaterialTextView;
import de.robv.android.xposed.*;
import java.util.*;

/** Lives inside Samsung's existing recording-area section; never opens another picker Activity. */
final class SamsungRecordingUi {
  static final class State {
    final Object controller;
    final Context context;
    final LinearLayout targetBox;
    final androidx.recyclerview.widget.RecyclerView rows;
    final MaterialTextView message;
    final MaterialButtonToggleGroup modes, audio;
    final List<Bundle> entries = new ArrayList<>();
    String selected;
    int mode, generation;
    boolean audioOnly;

    State(
        Object controller,
        Context context,
        LinearLayout targetBox,
        androidx.recyclerview.widget.RecyclerView rows,
        MaterialTextView message,
        MaterialButtonToggleGroup modes,
        MaterialButtonToggleGroup audio) {
      this.controller = controller;
      this.context = context;
      this.targetBox = targetBox;
      this.rows = rows;
      this.message = message;
      this.modes = modes;
      this.audio = audio;
    }
  }

  private static final Map<Object, java.lang.ref.WeakReference<State>> states = new WeakHashMap<>();
  private static final Handler main = new Handler(Looper.getMainLooper());

  static State state(Object controller) {
    var reference = states.get(controller);
    return reference == null ? null : reference.get();
  }

  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> controller =
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", loader);
    XposedBridge.hookAllMethods(
        controller,
        "initializeRadioButtonsForNewLayout",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled() || !rt.options.samsungApp()) return;
            try {
              attach(rt, p.thisObject, (View) p.args[0]);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
        });
    XposedBridge.hookAllMethods(
        controller,
        "showSoundSettingSuggestionPopup",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            State s = state(p.thisObject);
            if (s != null) updateStart(s);
          }
        });
  }

  private static int id(Context host, String name) {
    return host.getResources().getIdentifier(name, "id", "com.samsung.android.app.smartcapture");
  }

  private static int dp(Context c, int n) {
    return Math.round(c.getResources().getDisplayMetrics().density * n);
  }

  private static boolean checked(View v) {
    if (v instanceof CompoundButton b) return b.isChecked();
    if (v instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) if (checked(g.getChildAt(i))) return true;
    return false;
  }

  private static void attach(HookRuntime rt, Object controller, View root) throws Exception {
    Context host = root.getContext();
    Context module = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    Context c = new ContextThemeWrapper(module, R.style.AppTheme);
    View full = root.findViewById(id(host, "radio_button_full_screen")),
        partial = root.findViewById(id(host, "radio_button_partial_screen"));
    if (full == null || partial == null || full.getParent() != partial.getParent()) return;
    ViewGroup original = (ViewGroup) full.getParent();
    ViewGroup parent = (ViewGroup) original.getParent();
    int index = parent.indexOfChild(original);
    LinearLayout area = new LinearLayout(c);
    area.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 4));
    parent.addView(area, index + 1, lp);
    original.setVisibility(View.GONE);
    MaterialButtonToggleGroup modes = new MaterialButtonToggleGroup(c);
    modes.setSingleSelection(true);
    modes.setSelectionRequired(true);
    area.addView(modes, new LinearLayout.LayoutParams(-1, -2));
    int[] labels = {
      R.string.area_full, R.string.area_partial, R.string.area_app, R.string.area_window
    };
    for (int i = 0; i < (rt.options.singleWindow() ? 4 : 3); i++) {
      MaterialButton b =
          new MaterialButton(
              c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
      b.setId(View.generateViewId());
      b.setTag(i);
      b.setText(labels[i]);
      b.setTextSize(13);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(dp(c, 7), 0, dp(c, 7), 0);
      b.setInsetTop(0);
      b.setInsetBottom(0);
      modes.addView(b, new LinearLayout.LayoutParams(0, dp(c, 44), 1));
    }
    LinearLayout targetBox = new LinearLayout(c);
    targetBox.setOrientation(LinearLayout.VERTICAL);
    area.addView(targetBox, new LinearLayout.LayoutParams(-1, -2));
    MaterialTextView message = new MaterialTextView(c);
    message.setText(R.string.choose_target);
    message.setPadding(0, dp(c, 10), 0, dp(c, 4));
    targetBox.addView(message);
    TextInputLayout search = new TextInputLayout(c);
    search.setHint(c.getString(R.string.search_apps));
    TextInputEditText edit = new TextInputEditText(search.getContext());
    edit.setSingleLine(true);
    edit.setTextSize(14);
    search.addView(edit);
    targetBox.addView(search, new LinearLayout.LayoutParams(-1, -2));
    androidx.recyclerview.widget.RecyclerView rows =
        new androidx.recyclerview.widget.RecyclerView(c);
    rows.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(c));
    targetBox.addView(rows, new LinearLayout.LayoutParams(-1, dp(c, 174)));
    MaterialTextView audioLabel = new MaterialTextView(c);
    audioLabel.setText(R.string.audio_scope);
    audioLabel.setPadding(0, dp(c, 10), 0, dp(c, 4));
    targetBox.addView(audioLabel);
    MaterialButtonToggleGroup audio = new MaterialButtonToggleGroup(c);
    audio.setSingleSelection(true);
    audio.setSelectionRequired(true);
    targetBox.addView(audio, new LinearLayout.LayoutParams(-1, -2));
    for (int label : new int[] {R.string.audio_target_only, R.string.audio_every_app}) {
      MaterialButton b =
          new MaterialButton(
              c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
      b.setId(View.generateViewId());
      b.setText(label);
      b.setTextSize(13);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      audio.addView(b, new LinearLayout.LayoutParams(0, dp(c, 48), 1));
    }
    State s = new State(controller, c, targetBox, rows, message, modes, audio);
    states.put(controller, new java.lang.ref.WeakReference<>(s));
    s.audioOnly = rt.options.appAudioOnly();
    audio.check(audio.getChildAt(s.audioOnly ? 0 : 1).getId());
    audio.addOnButtonCheckedListener(
        (g, viewId, on) -> {
          if (on) s.audioOnly = viewId == audio.getChildAt(0).getId();
        });
    int initial = checked(partial) ? 1 : 0;
    s.mode = initial;
    modes.check(modes.getChildAt(initial).getId());
    targetBox.setVisibility(View.GONE);
    modes.addOnButtonCheckedListener(
        (g, buttonId, on) -> {
          if (!on) return;
          View button = g.findViewById(buttonId);
          s.mode = (int) button.getTag();
          s.selected = null;
          s.entries.clear();
          s.generation++;
          targetBox.setVisibility(s.mode >= 2 ? View.VISIBLE : View.GONE);
          if (s.mode == 1) partial.performClick();
          else full.performClick();
          if (s.mode >= 2) load(rt, s);
          else updateStart(s);
        });
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence x, int a, int count, int after) {}

          public void onTextChanged(CharSequence x, int start, int before, int count) {
            render(s, x.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private static void load(HookRuntime rt, State s) {
    int generation = s.generation;
    s.message.setText(R.string.loading_targets);
    s.rows.setAdapter(null);
    updateStart(s);
    Bundle query = new Bundle();
    query.putBoolean("windows", s.mode == 3);
    TargetBridge.request(
        rt,
        "list",
        query,
        new ResultReceiver(main) {
          @Override
          protected void onReceiveResult(int code, Bundle result) {
            if (generation != s.generation) return;
            if (code != android.app.Activity.RESULT_OK) {
              s.message.setText(R.string.target_unavailable);
              return;
            }
            ArrayList<Bundle> entries = result.getParcelableArrayList("entries", Bundle.class);
            s.entries.clear();
            if (entries != null) s.entries.addAll(entries);
            s.message.setText(
                entries == null || entries.isEmpty()
                    ? R.string.no_targets
                    : R.string.choose_target);
            render(s, "");
          }
        });
  }

  private static void render(State s, String query) {
    List<Bundle> filtered = new ArrayList<>();
    String filter = query.toLowerCase(Locale.ROOT);
    for (Bundle e : s.entries)
      if ((e.getString("label", "") + " " + e.getString("detail", ""))
          .toLowerCase(Locale.ROOT)
          .contains(filter)) filtered.add(e);
    s.rows.setAdapter(
        new androidx.recyclerview.widget.RecyclerView.Adapter<Row>() {
          @Override
          public Row onCreateViewHolder(ViewGroup parent, int type) {
            MaterialButton b =
                new MaterialButton(
                    s.context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setTextSize(14);
            b.setMaxLines(2);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setCheckable(true);
            b.setIconSize(dp(s.context, 28));
            b.setIconTint(null);
            b.setLayoutParams(
                new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(s.context, 58)));
            return new Row(b);
          }

          @Override
          public void onBindViewHolder(Row row, int position) {
            Bundle entry = filtered.get(position);
            MaterialButton b = (MaterialButton) row.itemView;
            String name = entry.getString("label", "");
            String detail = entry.getString("detail", "");
            b.setText(name + (detail.isEmpty() ? "" : "\n" + detail));
            b.setChecked(Objects.equals(s.selected, entry.getString("id")));
            b.setIcon(null);
            try {
              b.setIcon(
                  s.context.getPackageManager().getApplicationIcon(entry.getString("package", "")));
            } catch (Exception ignored) {
            }
            b.setOnClickListener(
                v -> {
                  s.selected = entry.getString("id");
                  s.message.setText(s.context.getString(R.string.selected_target, name));
                  notifyDataSetChanged();
                  updateStart(s);
                });
          }

          @Override
          public int getItemCount() {
            return filtered.size();
          }
        });
  }

  private static final class Row extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
    Row(View view) {
      super(view);
    }
  }

  static void updateStart(State s) {
    try {
      Dialog dialog =
          (Dialog) XposedHelpers.getObjectField(s.controller, "mSoundSettingSuggestionPopup");
      if (dialog == null) return;
      View start = dialog.findViewById(android.R.id.button1);
      if (start != null) start.setEnabled(s.mode < 2 || s.selected != null);
    } catch (Throwable ignored) {
    }
  }
}
