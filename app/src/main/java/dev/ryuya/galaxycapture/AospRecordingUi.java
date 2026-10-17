package dev.ryuya.galaxycapture;

import android.content.Context;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import dev.ryuya.galaxycapture.hook.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Adds a per-recording playback scope to the native Android recording permission dialog. */
final class AospRecordingUi {
  private record Key(IBinder cookie, int task) {}

  private record Choice(boolean audioOnly, long expires) {}

  private record State(View row, Spinner scope) {}

  private static final Map<Object, WeakReference<State>> states = new WeakHashMap<>();
  private static final Map<Key, Choice> choices = new ConcurrentHashMap<>();

  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> manager =
        Reflect.findClass(
            "com.android.systemui.screenrecord.ScreenRecordPermissionContentManager", loader);
    Hooks.hookAllMethods(
        manager,
        "bind",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled() || p.hasThrowable()) return;
            try {
              attach(rt, p.thisObject);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
        });
    Hooks.hookAllMethods(
        manager,
        "onItemSelected",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled() || !rt.guard.allowed() || state(p.thisObject) == null) return;
            try {
              refresh(p.thisObject);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
        });
    Hooks.hookAllMethods(
        manager,
        "requestScreenCapture",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            State state = state(p.thisObject);
            if (!rt.options.enabled() || state == null || p.args[0] == null) return;
            try {
              Key key = key(p.args[0]);
              if (key == null) throw new IllegalStateException("Audio capture target unavailable");
              Choice choice =
                  new Choice(
                      state.scope.getSelectedItemPosition() == 0,
                      SystemClock.elapsedRealtime() + 60000);
              choices.put(key, choice);
              rt.worker.postDelayed(() -> choices.remove(key, choice), 60000);
            } catch (Throwable error) {
              p.setThrowable(new IllegalStateException("Audio scope unavailable", error));
              rt.fail(error);
            }
          }
        });
  }

  private static State state(Object manager) {
    var reference = states.get(manager);
    return reference == null ? null : reference.get();
  }

  private static int dp(Context c, int n) {
    return Math.round(c.getResources().getDisplayMetrics().density * n);
  }

  private static void attach(HookRuntime rt, Object manager) throws Exception {
    if (state(manager) != null) return;
    Switch audio = (Switch) Reflect.getObjectField(manager, "audioSwitch");
    Spinner options = (Spinner) Reflect.getObjectField(manager, "options");
    View audioRow = (View) audio.getParent();
    ViewGroup parent = (ViewGroup) audioRow.getParent();
    if (!(parent instanceof LinearLayout))
      throw new IllegalStateException("Audio layout unavailable");
    Context c = options.getContext();
    Context strings = c.createPackageContext(BuildConfig.APPLICATION_ID, 0);
    LinearLayout row = new LinearLayout(c);
    row.setOrientation(LinearLayout.VERTICAL);
    TextView model = firstText((View) Reflect.getObjectField(manager, "tapsView"));
    TextView label = new TextView(c);
    label.setText(strings.getString(R.string.playback_audio_scope));
    label.setTextSize(14);
    if (model != null) {
      label.setTypeface(model.getTypeface());
      label.setTextColor(model.getTextColors());
    }
    label.setPadding(0, dp(c, 4), 0, 0);
    row.addView(label, new LinearLayout.LayoutParams(-1, -2));
    Spinner scope = new Spinner(c, Spinner.MODE_DROPDOWN);
    scope.setMinimumHeight(dp(c, 48));
    var adapter =
        new ArrayAdapter<String>(
            c,
            android.R.layout.simple_spinner_item,
            new String[] {
              strings.getString(R.string.audio_target_only),
              strings.getString(R.string.audio_every_app)
            });
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    scope.setAdapter(adapter);
    scope.setSelection(rt.options.appAudioOnly() ? 0 : 1);
    scope.setContentDescription(strings.getString(R.string.playback_audio_scope));
    scope.setId(View.generateViewId());
    label.setLabelFor(scope.getId());
    if (options.getBackground() != null && options.getBackground().getConstantState() != null)
      scope.setBackground(
          options.getBackground().getConstantState().newDrawable(c.getResources()).mutate());
    if (options.getPopupBackground() != null
        && options.getPopupBackground().getConstantState() != null)
      scope.setPopupBackgroundDrawable(
          options.getPopupBackground().getConstantState().newDrawable(c.getResources()).mutate());
    row.addView(scope, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
    View leading = ((ViewGroup) audioRow).getChildAt(0);
    ViewGroup.LayoutParams leadingSize = leading.getLayoutParams();
    int indent = Math.max(0, leadingSize.width);
    if (leadingSize instanceof ViewGroup.MarginLayoutParams margins)
      indent += margins.getMarginEnd();
    position.setMarginStart(indent);
    parent.addView(row, parent.indexOfChild(audioRow) + 1, position);
    State state = new State(row, scope);
    states.put(manager, new WeakReference<>(state));
    // The manager owns its container; these listeners retain the view state only while it is alive.
    CompoundButton.OnCheckedChangeListener previous =
        (CompoundButton.OnCheckedChangeListener)
            Reflect.getObjectField(audio, "mOnCheckedChangeListener");
    audio.setOnCheckedChangeListener(
        (button, checked) -> {
          if (previous != null) previous.onCheckedChanged(button, checked);
          refresh(manager, state);
        });
    AdapterView.OnItemSelectedListener original = options.getOnItemSelectedListener();
    options.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (original != null) original.onItemSelected(parent, view, position, id);
            refresh(manager, state);
          }

          public void onNothingSelected(AdapterView<?> parent) {
            if (original != null) original.onNothingSelected(parent);
            refresh(manager, state);
          }
        });
    refresh(manager, state);
  }

  private static TextView firstText(View view) {
    if (view instanceof TextView text && !(view instanceof CompoundButton)) return text;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView text = firstText(group.getChildAt(i));
        if (text != null) return text;
      }
    return null;
  }

  private static void refresh(Object manager) {
    refresh(manager, state(manager));
  }

  private static void refresh(Object manager, State state) {
    if (state == null) return;
    Object option = Reflect.getObjectField(manager, "selectedScreenShareOption");
    boolean app = Reflect.getIntField(option, "mode") == 0;
    Switch audio = (Switch) Reflect.getObjectField(manager, "audioSwitch");
    Spinner source = (Spinner) Reflect.getObjectField(manager, "options");
    Object selectedSource = source.getSelectedItem();
    String selected = selectedSource instanceof Enum<?> audioMode ? audioMode.name() : "";
    boolean playback =
        audio.isChecked() && (selected.equals("INTERNAL") || selected.equals("MIC_AND_INTERNAL"));
    state.row.setVisibility(app ? View.VISIBLE : View.GONE);
    state.scope.setEnabled(playback);
    state.row.setAlpha(playback ? 1f : .4f);
  }

  private static Key key(Object captureTarget) {
    Object cookie = Reflect.getObjectField(captureTarget, "launchCookie");
    if (cookie == null) return null;
    return new Key(
        (IBinder) Reflect.getObjectField(cookie, "binder"),
        Reflect.getIntField(captureTarget, "taskId"));
  }

  static boolean takeScope(Object recorder, boolean fallback) {
    Object target = Reflect.getObjectField(recorder, "mCaptureRegion");
    if (target == null) return fallback;
    Key key = key(target);
    Choice choice = key == null ? null : choices.remove(key);
    return choice != null && choice.expires >= SystemClock.elapsedRealtime()
        ? choice.audioOnly
        : fallback;
  }
}
