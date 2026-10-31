package dev.ryuya.galaxycapture

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.round

/* Adds a per-recording playback scope to the native Android recording permission dialog. */
object AospRecordingUi {
    private val states: MutableMap<Any?, WeakReference<AospRecordingUi.State?>?> =
        WeakHashMap<Any?, WeakReference<AospRecordingUi.State?>?>()
    private val choices: MutableMap<AospRecordingUi.Key?, AospRecordingUi.Choice?> =
        ConcurrentHashMap<AospRecordingUi.Key?, AospRecordingUi.Choice?>()

    @JvmStatic
    fun install(rt: HookRuntime, loader: ClassLoader?) {
        val manager =
            Reflect.findClass(
                "com.android.systemui.screenrecord.ScreenRecordPermissionContentManager",
                loader,
            )
        Hooks.hookAllMethods(
            manager,
            "bind",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!rt.options!!.enabled || p.hasThrowable()) return
                    try {
                        AospRecordingUi.attach(rt, p.thisObject)
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            manager,
            "onItemSelected",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (
                        !rt.options!!.enabled ||
                            !rt.guard.allowed() ||
                            AospRecordingUi.state(p.thisObject) == null
                    )
                        return
                    try {
                        refresh(p.thisObject)
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            manager,
            "requestScreenCapture",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state = AospRecordingUi.state(p.thisObject)
                    if (!rt.options!!.enabled || state == null || p!!.args!![0] == null) return
                    try {
                        val key = AospRecordingUi.key(p!!.args!![0])
                        checkNotNull(key) { "Audio capture target unavailable" }
                        val choice =
                            AospRecordingUi.Choice(
                                state.scope!!.getSelectedItemPosition() == 0,
                                SystemClock.elapsedRealtime() + 60000,
                            )
                        AospRecordingUi.choices.put(key, choice)
                        rt.worker.postDelayed(
                            Runnable { AospRecordingUi.choices.remove(key, choice) },
                            60000,
                        )
                    } catch (error: Throwable) {
                        p.setThrowable(IllegalStateException("Audio scope unavailable", error))
                        rt.fail(error)
                    }
                }
            },
        )
    }

    @JvmStatic
    private fun state(manager: Any?): AospRecordingUi.State? {
        val reference = AospRecordingUi.states.get(manager)
        return if (reference == null) null else reference.get()
    }

    @JvmStatic
    private fun dp(c: Context, n: Int): Int {
        return Math.round(c.getResources().getDisplayMetrics().density * n)
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun attach(rt: HookRuntime, manager: Any?) {
        if (AospRecordingUi.state(manager) != null) return
        val audio: Switch? = Reflect.getObjectField(manager!!, "audioSwitch") as Switch?
        val options: Spinner? = Reflect.getObjectField(manager!!, "options") as Spinner?
        val audioRow: View? = audio!!.getParent() as View?
        val parent: ViewGroup? = audioRow!!.getParent() as ViewGroup?
        check(parent is LinearLayout) { "Audio layout unavailable" }
        val c = options!!.getContext()
        val strings = c.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        val row = LinearLayout(c)
        row.setOrientation(LinearLayout.VERTICAL)
        val model =
            AospRecordingUi.firstText(Reflect.getObjectField(manager!!, "tapsView") as View?)
        val label = TextView(c)
        label.setText(strings.getString(R.string.playback_audio_scope))
        label.setTextSize(14f)
        if (model != null) {
            label.setTypeface(model.getTypeface())
            label.setTextColor(model.getTextColors())
        }
        label.setPadding(0, AospRecordingUi.dp(c, 4), 0, 0)
        row.addView(label, LayoutParams(-1, -2))
        val scope = Spinner(c, Spinner.MODE_DROPDOWN)
        scope.setMinimumHeight(AospRecordingUi.dp(c, 48))
        val adapter =
            ArrayAdapter<String?>(
                c,
                android.R.layout.simple_spinner_item,
                arrayOf<String?>(
                    strings.getString(R.string.audio_target_only),
                    strings.getString(R.string.audio_every_app),
                ),
            )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        scope.setAdapter(adapter)
        scope.setSelection(if (rt.options!!.appAudioOnly) 0 else 1)
        scope.setContentDescription(strings.getString(R.string.playback_audio_scope))
        scope.setId(View.generateViewId())
        label.setLabelFor(scope.getId())
        if (
            options!!.getBackground() != null &&
                options!!.getBackground().getConstantState() != null
        )
            scope.setBackground(
                options!!
                    .getBackground()
                    .getConstantState()!!
                    .newDrawable(c.getResources())
                    .mutate()
            )
        if (
            options!!.getPopupBackground() != null &&
                options!!.getPopupBackground().getConstantState() != null
        )
            scope.setPopupBackgroundDrawable(
                options!!
                    .getPopupBackground()
                    .getConstantState()!!
                    .newDrawable(c.getResources())
                    .mutate()
            )
        row.addView(scope, LayoutParams(-1, -2))
        val position = LayoutParams(-1, -2)
        val leading = (audioRow as ViewGroup).getChildAt(0)
        val leadingSize = leading.getLayoutParams()
        var indent = max(0, leadingSize.width)
        if (leadingSize is MarginLayoutParams) indent += leadingSize.getMarginEnd()
        position.setMarginStart(indent)
        parent.addView(row, parent.indexOfChild(audioRow) + 1, position)
        val state = AospRecordingUi.State(row, scope)
        AospRecordingUi.states.put(manager, WeakReference<AospRecordingUi.State?>(state))
        // The manager owns its container; these listeners retain the view state only while it is
        // alive.
        val previous: OnCheckedChangeListener? =
            Reflect.getObjectField(audio, "mOnCheckedChangeListener") as OnCheckedChangeListener?
        audio!!.setOnCheckedChangeListener(
            OnCheckedChangeListener { button: CompoundButton?, checked: Boolean ->
                if (previous != null) previous.onCheckedChanged(button!!, checked)
                AospRecordingUi.refresh(manager, state)
            }
        )
        val original = options!!.getOnItemSelectedListener()
        options!!.setOnItemSelectedListener(
            object : OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (original != null) original.onItemSelected(parent, view, position, id)
                    AospRecordingUi.refresh(manager, state)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    if (original != null) original.onNothingSelected(parent)
                    AospRecordingUi.refresh(manager, state)
                }
            }
        )
        AospRecordingUi.refresh(manager, state)
    }

    @JvmStatic
    private fun firstText(view: View?): TextView? {
        if (view is TextView && view !is CompoundButton) return view
        if (view is ViewGroup)
            for (i in 0 until view.getChildCount()) {
                val text = AospRecordingUi.firstText(view.getChildAt(i))
                if (text != null) return text
            }
        return null
    }

    @JvmStatic
    private fun refresh(
        manager: Any?,
        state: AospRecordingUi.State? = AospRecordingUi.state(manager),
    ) {
        if (state == null) return
        val option = Reflect.getObjectField(manager!!, "selectedScreenShareOption")
        val app: Boolean = Reflect.getIntField(option!!, "mode") == 0
        val audio: Switch? = Reflect.getObjectField(manager!!, "audioSwitch") as Switch?
        val source: Spinner? = Reflect.getObjectField(manager!!, "options") as Spinner?
        val selectedSource = source!!.getSelectedItem()
        val selected: String? = if (selectedSource is Enum<*>) selectedSource.name else ""
        val playback: Boolean =
            audio!!.isChecked() && (selected == "INTERNAL" || selected == "MIC_AND_INTERNAL")
        state.row!!.setVisibility(if (app) View.VISIBLE else View.GONE)
        state.scope!!.setEnabled(playback)
        state.row!!.setAlpha(if (playback) 1f else .4f)
    }

    @JvmStatic
    private fun key(captureTarget: Any?): AospRecordingUi.Key? {
        val cookie = Reflect.getObjectField(captureTarget!!, "launchCookie")
        if (cookie == null) return null
        return AospRecordingUi.Key(
            Reflect.getObjectField(cookie, "binder") as IBinder?,
            Reflect.getIntField(captureTarget!!, "taskId"),
        )
    }

    @JvmStatic
    fun takeScope(recorder: Any?, fallback: Boolean): Boolean {
        val target = Reflect.getObjectField(recorder!!, "mCaptureRegion")
        if (target == null) return fallback
        val key = AospRecordingUi.key(target)
        val choice: AospRecordingUi.Choice? =
            if (key == null) null else AospRecordingUi.choices.remove(key)
        return if (choice != null && choice.expires >= SystemClock.elapsedRealtime())
            choice.audioOnly
        else fallback
    }

    @JvmRecord
    private data class Key(val cookie: IBinder?, val task: Int) {
        override fun toString(): String = "Key[cookie=$cookie, task=$task]"
    }

    @JvmRecord
    private data class Choice(val audioOnly: Boolean, val expires: Long) {
        override fun toString(): String = "Choice[audioOnly=$audioOnly, expires=$expires]"
    }

    @JvmRecord
    private data class State(val row: View?, val scope: Spinner?) {
        override fun toString(): String = "State[row=$row, scope=$scope]"
    }
}
