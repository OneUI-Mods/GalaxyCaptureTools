package dev.ryuya.galaxycapture;

import android.app.Dialog;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import de.robv.android.xposed.*;
import java.util.*;

/** Lives inside Samsung's existing recording-area section; never opens another picker Activity. */
final class SamsungRecordingUi {
  static final class State {
    final Object controller;
    final Context context;
    final LinearLayout targetBox;
    final ListView rows;
    final TextView message;
    final List<View> modes;
    final Context strings;
    final List<Bundle> entries = new ArrayList<>();
    String selected;
    String query = "";
    int mode, generation;
    boolean audioOnly;

    State(
        Object controller,
        Context context,
        LinearLayout targetBox,
        ListView rows,
        TextView message,
        List<View> modes,
        Context strings) {
      this.controller = controller;
      this.context = context;
      this.targetBox = targetBox;
      this.rows = rows;
      this.message = message;
      this.modes = modes;
      this.strings = strings;
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
    Context c = host;
    Context strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
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
    // Preserve the actual Samsung controls and their native graphics, typography and radio state.
    List<View> modes = new ArrayList<>();
    View.OnClickListener fullClick = listener(full), partialClick = listener(partial);
    original.removeView(full);
    original.removeView(partial);
    modes.add(full);
    modes.add(partial);
    modes.add(graphicalChoice(host, strings.getString(R.string.area_app), full, false));
    if (rt.options.singleWindow())
      modes.add(graphicalChoice(host, strings.getString(R.string.area_window), full, true));
    for (int i = 0; i < modes.size(); i += 2) {
      LinearLayout row = new LinearLayout(host);
      row.setGravity(Gravity.TOP);
      area.addView(row, new LinearLayout.LayoutParams(-1, -2));
      for (int j = i; j < Math.min(i + 2, modes.size()); j++) {
        View choice = modes.get(j);
        choice.setId(j < 2 ? choice.getId() : View.generateViewId());
        row.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
      }
      if (i + 1 == modes.size())
        row.addView(new View(host), new LinearLayout.LayoutParams(0, 1, 1));
    }
    LinearLayout targetBox = new LinearLayout(c);
    targetBox.setOrientation(LinearLayout.VERTICAL);
    area.addView(targetBox, new LinearLayout.LayoutParams(-1, -2));
    TextView message = nativeText(host, full);
    message.setText(strings.getString(R.string.choose_target));
    message.setPadding(0, dp(c, 12), 0, dp(c, 8));
    targetBox.addView(message);
    EditText edit = new EditText(host);
    edit.setHint(strings.getString(R.string.search_apps));
    edit.setSingleLine(true);
    edit.setTextSize(14);
    edit.setTypeface(message.getTypeface());
    targetBox.addView(edit, new LinearLayout.LayoutParams(-1, -2));
    ListView rows = new ListView(host);
    rows.setDivider(null);
    targetBox.addView(rows, new LinearLayout.LayoutParams(-1, dp(c, 180)));
    // The outer native dialog scrolls too. Let the bounded list handle drags within its rows.
    rows.setOnTouchListener(
        (v, e) -> {
          v.getParent()
              .requestDisallowInterceptTouchEvent(
                  e.getActionMasked() != MotionEvent.ACTION_UP
                      && e.getActionMasked() != MotionEvent.ACTION_CANCEL);
          return false;
        });
    TextView audioLabel = nativeText(host, full);
    audioLabel.setText(strings.getString(R.string.audio_scope));
    audioLabel.setPadding(0, dp(c, 16), 0, dp(c, 8));
    targetBox.addView(audioLabel);
    View soundModel = root.findViewById(id(host, "radio_button_sound_media"));
    if (soundModel == null) throw new IllegalStateException("Native sound control missing");
    LinearLayout audio = new LinearLayout(host);
    targetBox.addView(audio, new LinearLayout.LayoutParams(-1, -2));
    List<View> audioChoices = new ArrayList<>();
    for (int label : new int[] {R.string.audio_target_only, R.string.audio_every_app}) {
      View choice = (View) XposedHelpers.newInstance(soundModel.getClass(), host);
      ImageView modelImage = (ImageView) XposedHelpers.getObjectField(soundModel, "imageView");
      ImageView image = (ImageView) XposedHelpers.getObjectField(choice, "imageView");
      ViewGroup.LayoutParams size = image.getLayoutParams();
      size.width = modelImage.getLayoutParams().width;
      size.height = modelImage.getLayoutParams().height;
      image.setLayoutParams(size);
      image.setImageDrawable(
          modelImage.getDrawable().getConstantState().newDrawable(host.getResources()).mutate());
      TextView text = (TextView) XposedHelpers.getObjectField(choice, "textView");
      text.setText(strings.getString(label));
      text.setTextSize(
          android.util.TypedValue.COMPLEX_UNIT_PX,
          ((TextView) XposedHelpers.getObjectField(soundModel, "textView")).getTextSize());
      audio.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
      audioChoices.add(choice);
    }
    State s = new State(controller, c, targetBox, rows, message, modes, strings);
    states.put(controller, new java.lang.ref.WeakReference<>(s));
    s.audioOnly = rt.options.appAudioOnly();
    for (int i = 0; i < audioChoices.size(); i++) {
      final boolean only = i == 0;
      View choice = audioChoices.get(i);
      XposedHelpers.callMethod(choice, "setSoundButtonSelectedState", s.audioOnly == only);
      choice.setOnClickListener(
          v -> {
            s.audioOnly = only;
            for (int n = 0; n < audioChoices.size(); n++)
              XposedHelpers.callMethod(
                  audioChoices.get(n), "setSoundButtonSelectedState", (n == 0) == only);
          });
    }
    int initial = checked(partial) ? 1 : 0;
    s.mode = initial;
    targetBox.setVisibility(View.GONE);
    for (int i = 0; i < modes.size(); i++) {
      final int mode = i;
      XposedHelpers.callMethod(modes.get(i), "setScreenButtonSelectedState", i == initial);
      modes
          .get(i)
          .setOnClickListener(
              v -> {
                if (mode == s.mode) return;
                if (mode == 1) partialClick.onClick(partial);
                else fullClick.onClick(full);
                s.mode = mode;
                s.selected = null;
                s.entries.clear();
                s.generation++;
                edit.setText("");
                for (int n = 0; n < modes.size(); n++)
                  XposedHelpers.callMethod(modes.get(n), "setScreenButtonSelectedState", n == mode);
                targetBox.setVisibility(mode >= 2 ? View.VISIBLE : View.GONE);
                if (mode >= 2) load(rt, s);
                else updateStart(s);
              });
    }
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence x, int a, int count, int after) {}

          public void onTextChanged(CharSequence x, int start, int before, int count) {
            render(s, x.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private static View.OnClickListener listener(View v) {
    Object info = XposedHelpers.callMethod(v, "getListenerInfo");
    View.OnClickListener result =
        (View.OnClickListener) XposedHelpers.getObjectField(info, "mOnClickListener");
    if (result == null) throw new IllegalStateException("Native recording-area listener missing");
    return result;
  }

  private static View graphicalChoice(Context host, String label, View model, boolean window) {
    View choice = (View) XposedHelpers.newInstance(model.getClass(), host);
    ImageView modelImage = (ImageView) XposedHelpers.getObjectField(model, "imageView");
    ImageView image = (ImageView) XposedHelpers.getObjectField(choice, "imageView");
    TextView text = (TextView) XposedHelpers.getObjectField(choice, "textView");
    text.setText(label);
    text.setTextSize(
        android.util.TypedValue.COMPLEX_UNIT_PX,
        ((TextView) XposedHelpers.getObjectField(model, "textView")).getTextSize());
    ViewGroup.LayoutParams size = image.getLayoutParams();
    size.width = modelImage.getLayoutParams().width;
    size.height = modelImage.getLayoutParams().height;
    image.setLayoutParams(size);
    image.setPadding(0, 0, 0, 0);
    image.setImageDrawable(new RecordingAreaGraphic(window));
    choice.setContentDescription(label);
    choice.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    return choice;
  }

  private static void load(HookRuntime rt, State s) {
    int generation = s.generation;
    s.message.setText(s.strings.getString(R.string.loading_targets));
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
              s.message.setText(s.strings.getString(R.string.target_unavailable));
              return;
            }
            ArrayList<Bundle> entries = result.getParcelableArrayList("entries", Bundle.class);
            s.entries.clear();
            if (entries != null) s.entries.addAll(entries);
            s.message.setText(
                s.strings.getString(
                    entries == null || entries.isEmpty()
                        ? R.string.no_targets
                        : R.string.choose_target));
            render(s, s.query);
          }
        });
  }

  private static void render(State s, String query) {
    s.query = query;
    List<Bundle> filtered = new ArrayList<>();
    String filter = query.toLowerCase(Locale.ROOT);
    for (Bundle e : s.entries)
      if ((e.getString("label", "") + " " + e.getString("detail", ""))
          .toLowerCase(Locale.ROOT)
          .contains(filter)) filtered.add(e);
    s.rows.setAdapter(
        new BaseAdapter() {
          @Override
          public int getCount() {
            return filtered.size();
          }

          @Override
          public Object getItem(int position) {
            return filtered.get(position);
          }

          @Override
          public long getItemId(int position) {
            return position;
          }

          @Override
          public View getView(int position, View recycled, ViewGroup parent) {
            Bundle entry = filtered.get(position);
            LinearLayout row = new LinearLayout(s.context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(s.context, 60));
            row.setPadding(dp(s.context, 8), dp(s.context, 6), dp(s.context, 8), dp(s.context, 6));
            ImageView icon = new ImageView(s.context);
            try {
              icon.setImageDrawable(
                  s.context.getPackageManager().getApplicationIcon(entry.getString("package", "")));
            } catch (Exception ignored) {
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(s.context, 32), dp(s.context, 32)));
            TextView text = nativeText(s.context, s.modes.get(0));
            String name = entry.getString("label", ""), detail = entry.getString("detail", "");
            text.setText(name + (detail.isEmpty() ? "" : "\n" + detail));
            text.setTextSize(14);
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding(dp(s.context, 12), 0, dp(s.context, 8), 0);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            RadioButton radio = new RadioButton(s.context);
            RadioButton model =
                (RadioButton) XposedHelpers.getObjectField(s.modes.get(0), "radioButton");
            radio.setButtonTintList(model.getButtonTintList());
            radio.setChecked(Objects.equals(s.selected, entry.getString("id")));
            radio.setClickable(false);
            radio.setFocusable(false);
            row.addView(radio, new LinearLayout.LayoutParams(-2, dp(s.context, 48)));
            row.setOnClickListener(
                v -> {
                  s.selected = entry.getString("id");
                  s.message.setText(s.strings.getString(R.string.selected_target, name));
                  notifyDataSetChanged();
                  updateStart(s);
                });
            return row;
          }
        });
  }

  private static TextView nativeText(Context c, View model) {
    TextView text = new TextView(c);
    TextView original = (TextView) XposedHelpers.getObjectField(model, "textView");
    text.setTypeface(original.getTypeface());
    text.setTextColor((int) XposedHelpers.getIntField(model, "unselectedColor"));
    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, original.getTextSize());
    return text;
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
