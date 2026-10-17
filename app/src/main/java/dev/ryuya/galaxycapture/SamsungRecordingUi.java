package dev.ryuya.galaxycapture;

import android.app.Dialog;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import de.robv.android.xposed.*;
import java.util.*;

/** Samsung-native recording choices and a separate, reversible target-selection dialog. */
final class SamsungRecordingUi {
  static final class State {
    final Object controller;
    final Context context;
    final LinearLayout targetBox;
    final TextView summary;
    ListView rows;
    TextView message, section, other;
    Dialog picker;
    final List<View> modes;
    final Context strings;
    final List<Bundle> entries = new ArrayList<>();
    String selected;
    String selectedLabel, selectedKey;
    boolean allApps;
    int pickerMode;
    String query = "";
    int mode, generation;
    boolean audioOnly;

    State(
        Object controller,
        Context context,
        LinearLayout targetBox,
        TextView summary,
        List<View> modes,
        Context strings) {
      this.controller = controller;
      this.context = context;
      this.targetBox = targetBox;
      this.summary = summary;
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
    TextView summary = nativeText(host, full);
    summary.setPadding(0, dp(c, 12), 0, dp(c, 12));
    summary.setMinimumHeight(dp(c, 48));
    selectable(summary);
    targetBox.addView(summary);
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
    State s = new State(controller, c, targetBox, summary, modes, strings);
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
    Runnable selectFull = () -> fullClick.onClick(full);
    summary.setOnClickListener(v -> openPicker(rt, s, s.mode, selectFull));
    for (int i = 0; i < modes.size(); i++) {
      final int mode = i;
      XposedHelpers.callMethod(modes.get(i), "setScreenButtonSelectedState", i == initial);
      modes
          .get(i)
          .setOnClickListener(
              v -> {
                if (mode >= 2) {
                  openPicker(rt, s, mode, selectFull);
                  return;
                }
                if (mode == 1) partialClick.onClick(partial);
                else fullClick.onClick(full);
                s.mode = mode;
                s.selected = null;
                s.selectedLabel = null;
                refreshSelection(s);
              });
    }
  }

  private static void refreshSelection(State s) {
    for (int i = 0; i < s.modes.size(); i++)
      XposedHelpers.callMethod(s.modes.get(i), "setScreenButtonSelectedState", i == s.mode);
    s.targetBox.setVisibility(s.mode >= 2 ? View.VISIBLE : View.GONE);
    s.summary.setText(s.strings.getString(R.string.selected_target, s.selectedLabel) + "  ›");
    s.summary.setContentDescription(s.strings.getString(R.string.change_target, s.selectedLabel));
    updateStart(s);
  }

  private static void selectable(View v) {
    android.util.TypedValue value = new android.util.TypedValue();
    if (v.getContext()
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackground, value, true))
      v.setBackgroundResource(value.resourceId);
  }

  private static void openPicker(HookRuntime rt, State s, int mode, Runnable selectFull) {
    if (s.picker != null) return;
    try {
      Dialog parent =
          (Dialog) XposedHelpers.getObjectField(s.controller, "mSoundSettingSuggestionPopup");
      if (parent == null || !parent.isShowing()) return;
      // Use the very same Samsung AlertDialog builder and theme as the recording start dialog.
      int theme =
          s.context
              .getResources()
              .getIdentifier(
                  "RecorderDialogAlertTheme", "style", "com.samsung.android.app.smartcapture");
      Object builder =
          XposedHelpers.newInstance(
              XposedHelpers.findClass("M4.c", s.controller.getClass().getClassLoader()),
              s.context,
              theme);
      Object params = XposedHelpers.getObjectField(builder, "f6412b");
      Context themed = (Context) XposedHelpers.getObjectField(params, "f22202a");
      XposedHelpers.setObjectField(
          params,
          "f22205d",
          s.strings.getString(mode == 3 ? R.string.choose_window : R.string.choose_app));
      XposedHelpers.setObjectField(params, "i", s.strings.getString(R.string.back_to_recording));
      LinearLayout content = new LinearLayout(themed);
      content.setOrientation(LinearLayout.VERTICAL);
      content.setPadding(dp(themed, 24), 0, dp(themed, 24), 0);
      s.pickerMode = mode;
      s.query = "";
      s.allApps = false;
      s.entries.clear();
      EditText search = new EditText(themed);
      search.setHint(s.strings.getString(R.string.search_apps));
      search.setSingleLine(true);
      search.setTextSize(16);
      search.setTypeface(s.summary.getTypeface());
      search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
      content.addView(search, new LinearLayout.LayoutParams(-1, dp(themed, 56)));
      LinearLayout heading = new LinearLayout(themed);
      heading.setGravity(Gravity.CENTER_VERTICAL);
      s.section = nativeText(themed, s.modes.get(0));
      s.section.setTextSize(14);
      heading.addView(s.section, new LinearLayout.LayoutParams(0, dp(themed, 48), 1));
      s.section.setGravity(Gravity.CENTER_VERTICAL);
      s.other = nativeText(themed, s.modes.get(0));
      s.other.setTextSize(14);
      s.other.setGravity(Gravity.CENTER);
      s.other.setPadding(dp(themed, 12), 0, 0, 0);
      s.other.setTextColor(XposedHelpers.getIntField(s.modes.get(0), "selectedColor"));
      selectable(s.other);
      heading.addView(s.other, new LinearLayout.LayoutParams(-2, dp(themed, 48)));
      content.addView(heading);
      FrameLayout listBox = new FrameLayout(themed);
      s.rows = new ListView(themed);
      s.rows.setDivider(null);
      s.rows.setClipToPadding(false);
      s.rows.setPadding(0, 0, 0, dp(themed, 8));
      listBox.addView(s.rows, new FrameLayout.LayoutParams(-1, -1));
      s.message = nativeText(themed, s.modes.get(0));
      s.message.setTextSize(14);
      s.message.setGravity(Gravity.CENTER);
      s.message.setPadding(dp(themed, 8), dp(themed, 16), dp(themed, 8), dp(themed, 16));
      listBox.addView(s.message, new FrameLayout.LayoutParams(-1, -1));
      s.rows.setEmptyView(s.message);
      content.addView(listBox, new LinearLayout.LayoutParams(-1, 0, 1));
      int available = themed.getResources().getDisplayMetrics().heightPixels;
      int height =
          Math.max(
              dp(themed, 180),
              Math.min(dp(themed, 520), (int) (available * .82f) - dp(themed, 140)));
      FrameLayout frame = new FrameLayout(themed);
      frame.addView(content, new FrameLayout.LayoutParams(-1, height));
      XposedHelpers.setObjectField(params, "f22214o", frame);
      Dialog picker = (Dialog) XposedHelpers.callMethod(builder, "f");
      s.picker = picker;
      Window window = picker.getWindow();
      window.setType(parent.getWindow().getAttributes().type);
      window.setSoftInputMode(
          WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
              | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
      picker.setCanceledOnTouchOutside(false);
      View decor = parent.getWindow().getDecorView();
      View.OnAttachStateChangeListener lifecycle =
          new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}

            public void onViewDetachedFromWindow(View v) {
              picker.dismiss();
            }
          };
      decor.addOnAttachStateChangeListener(lifecycle);
      picker.setOnDismissListener(
          d -> {
            decor.removeOnAttachStateChangeListener(lifecycle);
            if (s.picker != picker) return;
            s.picker = null;
            s.generation++;
            if (decor.isAttachedToWindow()) parent.show();
          });
      s.other.setOnClickListener(
          v -> {
            s.allApps = !s.allApps;
            render(s, s.query, selectFull);
          });
      search.addTextChangedListener(
          new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int count, int after) {}

            public void onTextChanged(CharSequence x, int start, int before, int count) {
              render(s, x.toString(), selectFull);
            }

            public void afterTextChanged(Editable e) {}
          });
      picker.show();
      // hide() keeps Samsung's cancellation/service lifecycle intact; dismiss() would abort setup.
      parent.hide();
      load(rt, s, selectFull);
    } catch (Throwable error) {
      if (s.picker != null) s.picker.dismiss();
      rt.fail(error);
    }
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

  private static void load(HookRuntime rt, State s, Runnable selectFull) {
    int generation = ++s.generation;
    s.message.setText(s.strings.getString(R.string.loading_targets));
    s.rows.setAdapter(null);
    updateHeading(s);
    Bundle query = new Bundle();
    query.putBoolean("windows", s.pickerMode == 3);
    boolean[] received = {false};
    main.postDelayed(
        () -> {
          if (!received[0] && generation == s.generation && s.picker != null)
            s.message.setText(s.strings.getString(R.string.target_unavailable));
        },
        5000);
    TargetBridge.request(
        rt,
        "list",
        query,
        new ResultReceiver(main) {
          @Override
          protected void onReceiveResult(int code, Bundle result) {
            if (generation != s.generation || s.picker == null) return;
            received[0] = true;
            if (code != android.app.Activity.RESULT_OK) {
              s.message.setText(s.strings.getString(R.string.target_unavailable));
              return;
            }
            ArrayList<Bundle> entries = result.getParcelableArrayList("entries", Bundle.class);
            s.entries.clear();
            if (entries != null) s.entries.addAll(entries);
            render(s, s.query, selectFull);
          }
        });
  }

  private static void updateHeading(State s) {
    boolean search = !s.query.isBlank();
    s.section.setText(
        s.strings.getString(
            s.pickerMode == 3
                ? R.string.area_window
                : search
                    ? R.string.search_results
                    : s.allApps ? R.string.all_apps : R.string.recent_apps));
    s.other.setVisibility(s.pickerMode == 3 || search ? View.GONE : View.VISIBLE);
    s.other.setText(s.strings.getString(s.allApps ? R.string.recent_apps : R.string.other_apps));
  }

  private static void render(State s, String query, Runnable selectFull) {
    s.query = query;
    updateHeading(s);
    List<Bundle> filtered = new ArrayList<>();
    String filter = query.strip().toLowerCase(Locale.ROOT);
    boolean search = !filter.isEmpty();
    for (Bundle e : s.entries) {
      if (s.pickerMode != 3 && !s.allApps && !search && e.getInt("recent_order", -1) < 0) continue;
      if ((e.getString("label", "")
              + " "
              + e.getString("detail", "")
              + " "
              + e.getString("package", ""))
          .toLowerCase(Locale.ROOT)
          .contains(filter)) filtered.add(e);
    }
    if (s.allApps || search)
      filtered.sort(
          Comparator.comparing(b -> b.getString("label", ""), java.text.Collator.getInstance()));
    s.message.setText(
        s.strings.getString(
            search
                ? R.string.no_search_results
                : s.pickerMode == 3
                    ? R.string.no_targets
                    : s.allApps ? R.string.no_search_results : R.string.no_recent_apps));
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
            Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(c, 64));
            row.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
            selectable(row);
            ImageView icon = new ImageView(c);
            try {
              icon.setImageDrawable(
                  c.getPackageManager().getApplicationIcon(entry.getString("package", "")));
            } catch (Exception ignored) {
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)));
            TextView text = nativeText(c, s.modes.get(0));
            String name = entry.getString("label", ""), detail = entry.getString("detail", "");
            text.setText(name + (detail.isEmpty() ? "" : "\n" + detail));
            text.setTextSize(16);
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding(dp(c, 16), 0, dp(c, 8), 0);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            RadioButton radio = new RadioButton(c);
            RadioButton model =
                (RadioButton) XposedHelpers.getObjectField(s.modes.get(0), "radioButton");
            radio.setButtonTintList(model.getButtonTintList());
            radio.setChecked(
                s.mode == s.pickerMode && Objects.equals(s.selectedKey, entry.getString("key")));
            radio.setClickable(false);
            radio.setFocusable(false);
            radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(radio, new LinearLayout.LayoutParams(-2, dp(c, 48)));
            row.setOnClickListener(
                v -> {
                  selectFull.run();
                  s.mode = s.pickerMode;
                  s.selected = entry.getString("id");
                  s.selectedLabel = name;
                  s.selectedKey = entry.getString("key");
                  refreshSelection(s);
                  if (s.picker != null) s.picker.dismiss();
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
