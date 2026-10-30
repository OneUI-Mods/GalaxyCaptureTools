package dev.ryuya.galaxycapture

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.text.Editable
import android.text.TextUtils.TruncateAt
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.ref.WeakReference
import java.text.Collator
import java.util.ArrayList
import java.util.Comparator
import java.util.HashSet
import java.util.WeakHashMap
import java.util.function.Function
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/* Samsung-native recording choices and a separate, reversible target-selection dialog. */
object SamsungRecordingUi {
    private val states: MutableMap<Any?, WeakReference<SamsungRecordingUi.State?>?> =
        WeakHashMap<Any?, WeakReference<SamsungRecordingUi.State?>?>()
    private val pendingRoots: MutableMap<Any?, WeakReference<View?>?> =
        WeakHashMap<Any?, WeakReference<View?>?>()
    private val main: Handler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun state(controller: Any?): SamsungRecordingUi.State? {
        val reference = SamsungRecordingUi.states.get(controller)
        return if (reference == null) null else reference.get()
    }

    @JvmStatic
    fun install(rt: HookRuntime, loader: ClassLoader?) {
        // Configuration arrives off the UI thread. A cold-started popup can already be visible
        // before the first read completes, so finish that popup when the settings become ready.
        rt.onOptionsChanged(
            Runnable {
                SamsungRecordingUi.main.post(
                    Runnable {
                        for (owner in ArrayList<Any?>(SamsungRecordingUi.pendingRoots.keys)) {
                            val reference = SamsungRecordingUi.pendingRoots.get(owner)
                            val root: View? = if (reference == null) null else reference.get()
                            if (root == null) SamsungRecordingUi.pendingRoots.remove(owner)
                            else if (root.isAttachedToWindow())
                                SamsungRecordingUi.attachPending(rt, owner!!)
                        }
                    }
                )
            }
        )
        val controller =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController",
                loader,
            )
        Hooks.hookAllMethods(
            controller,
            "initializeRadioButtonsForNewLayout",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    SamsungRecordingUi.pendingRoots.put(
                        p.thisObject,
                        WeakReference<View?>(p!!.args!![0] as View?),
                    )
                    SamsungRecordingUi.attachPending(rt, p.thisObject!!)
                }
            },
        )
        Hooks.hookAllMethods(
            controller,
            "showSoundSettingSuggestionPopup",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    SamsungRecordingUi.attachPending(rt, p.thisObject!!)
                    val s = SamsungRecordingUi.state(p.thisObject)
                    if (s != null) SamsungRecordingUi.updateStart(s)
                }
            },
        )
    }

    @JvmStatic
    private fun attachPending(rt: HookRuntime, owner: Any) {
        if (!rt.options!!.enabled || !rt.options!!.samsungApp) return
        val reference = SamsungRecordingUi.pendingRoots.get(owner)
        val root: View? = if (reference == null) null else reference.get()
        if (root == null) return
        try {
            val current = SamsungRecordingUi.state(owner)
            if (current == null || current.sourceRoot !== root)
                SamsungRecordingUi.attach(rt, owner, root)
            val attached = SamsungRecordingUi.state(owner)
            if (attached != null && attached.sourceRoot === root)
                SamsungRecordingUi.pendingRoots.remove(owner)
        } catch (error: Throwable) {
            SamsungRecordingUi.pendingRoots.remove(owner)
            rt.fail(error)
        }
    }

    @JvmStatic
    private fun id(host: Context, name: String?): Int {
        return host.getResources().getIdentifier(name, "id", "com.samsung.android.app.smartcapture")
    }

    @JvmStatic
    private fun dp(c: Context, n: Int): Int {
        return Math.round(c.getResources().getDisplayMetrics().density * n)
    }

    @JvmStatic
    private fun checked(v: View?): Boolean {
        if (v is CompoundButton) return v.isChecked()
        if (v is ViewGroup)
            for (i in 0 until v.getChildCount()) if (SamsungRecordingUi.checked(v.getChildAt(i)))
                return true
        return false
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun attach(rt: HookRuntime, controller: Any, root: View) {
        val host = root.getContext()
        val c: Context? = host
        val strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        val full = root.findViewById<View?>(SamsungRecordingUi.id(host, "radio_button_full_screen"))
        val partial =
            root.findViewById<View?>(SamsungRecordingUi.id(host, "radio_button_partial_screen"))
        if (full == null || partial == null || full.getParent() !== partial.getParent()) return
        val original: ViewGroup? = full.getParent() as ViewGroup?
        val parent: ViewGroup? = original!!.getParent() as ViewGroup?
        val index = parent!!.indexOfChild(original)
        val area = LinearLayout(c)
        area.setOrientation(LinearLayout.VERTICAL)
        val lp = android.widget.LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(
            SamsungRecordingUi.dp(c!!, 16),
            SamsungRecordingUi.dp(c!!, 8),
            SamsungRecordingUi.dp(c!!, 16),
            SamsungRecordingUi.dp(c!!, 4),
        )
        parent!!.addView(area, index + 1, lp)
        original!!.setVisibility(View.GONE)
        // Preserve the actual Samsung controls and their native graphics, typography and radio
        // state.
        val modes = ArrayList<View?>()
        val fullClick = SamsungRecordingUi.listener(full)
        val partialClick = SamsungRecordingUi.listener(partial)
        original!!.removeView(full)
        original!!.removeView(partial)
        modes.add(full)
        modes.add(partial)
        modes.add(
            SamsungRecordingUi.graphicalChoice(
                host,
                strings.getString(R.string.area_app),
                full,
                false,
            )
        )
        if (rt.options!!.singleWindow)
            modes.add(
                SamsungRecordingUi.graphicalChoice(
                    host,
                    strings.getString(R.string.area_window),
                    full,
                    true,
                )
            )
        run {
            var i: Int = 0
            while (i < modes.size) {
                val row = LinearLayout(host)
                row.setGravity(Gravity.TOP)
                area.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
                for (j in i until min(i + 2, modes.size)) {
                    val choice = modes.get(j)
                    choice!!.setId(if (j < 2) choice!!.getId() else View.generateViewId())
                    row.addView(choice, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (i + 1 == modes.size)
                    row.addView(View(host), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
                i += 2
            }
        }
        val targetBox = LinearLayout(c)
        targetBox.setOrientation(LinearLayout.VERTICAL)
        area.addView(targetBox, android.widget.LinearLayout.LayoutParams(-1, -2))
        val summary = SamsungRecordingUi.nativeText(host, full)
        summary.setPadding(0, SamsungRecordingUi.dp(c!!, 12), 0, SamsungRecordingUi.dp(c!!, 12))
        summary.setMinimumHeight(SamsungRecordingUi.dp(c!!, 48))
        SamsungRecordingUi.selectable(summary)
        targetBox.addView(summary)
        val audioLabel = SamsungRecordingUi.nativeText(host, full)
        audioLabel.setText(strings.getString(R.string.audio_scope))
        audioLabel.setPadding(0, SamsungRecordingUi.dp(c!!, 16), 0, SamsungRecordingUi.dp(c!!, 8))
        targetBox.addView(audioLabel)
        val soundModel =
            root.findViewById<View?>(SamsungRecordingUi.id(host, "radio_button_sound_media"))
        checkNotNull(soundModel) { "Native sound control missing" }
        val audio = LinearLayout(host)
        targetBox.addView(audio, android.widget.LinearLayout.LayoutParams(-1, -2))
        val audioChoices = ArrayList<View?>()
        for (label in intArrayOf(R.string.audio_target_only, R.string.audio_every_app)) {
            val choice: View? = Reflect.newInstance(soundModel.javaClass, host) as View?
            val modelImage: ImageView? =
                Reflect.getObjectField(soundModel, "imageView") as ImageView?
            val image: ImageView? = Reflect.getObjectField(choice!!, "imageView") as ImageView?
            val size = image!!.getLayoutParams()
            size.width = modelImage!!.getLayoutParams().width
            size.height = modelImage!!.getLayoutParams().height
            image!!.setLayoutParams(size)
            image!!.setImageDrawable(
                modelImage!!
                    .getDrawable()
                    .getConstantState()!!
                    .newDrawable(host.getResources())
                    .mutate()
            )
            val text: TextView? = Reflect.getObjectField(choice!!, "textView") as TextView?
            text!!.setText(strings.getString(label))
            text!!.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                (Reflect.getObjectField(soundModel, "textView") as TextView).getTextSize(),
            )
            audio.addView(choice, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            audioChoices.add(choice)
        }
        val s = SamsungRecordingUi.State(controller, root, c!!, targetBox, summary, modes, strings)
        SamsungRecordingUi.states.put(controller, WeakReference<SamsungRecordingUi.State?>(s))
        s.audioOnly = rt.options!!.appAudioOnly
        for (i in audioChoices.indices) {
            val only: Boolean = i == 0
            val choice = audioChoices.get(i)
            Reflect.callMethod(choice!!, "setSoundButtonSelectedState", s.audioOnly == only)
            choice!!.setOnClickListener(
                OnClickListener { v: View? ->
                    s.audioOnly = only
                    for (n in audioChoices.indices) Reflect.callMethod(
                        audioChoices.get(n)!!,
                        "setSoundButtonSelectedState",
                        (n == 0) == only,
                    )
                }
            )
        }
        val initial: Int = if (SamsungRecordingUi.checked(partial)) 1 else 0
        s.mode = initial
        targetBox.setVisibility(View.GONE)
        val selectFull = Runnable { fullClick!!.onClick(full) }
        summary.setOnClickListener(
            OnClickListener { v: View? -> SamsungRecordingUi.openPicker(rt, s, s.mode, selectFull) }
        )
        for (i in modes.indices) {
            val mode: Int = i
            Reflect.callMethod(modes.get(i)!!, "setScreenButtonSelectedState", i == initial)
            modes
                .get(i)!!
                .setOnClickListener(
                    OnClickListener { v: View? ->
                        if (mode >= 2) {
                            SamsungRecordingUi.openPicker(rt, s, mode, selectFull)
                            return@OnClickListener
                        }
                        if (mode == 1) partialClick!!.onClick(partial)
                        else fullClick!!.onClick(full)
                        s.mode = mode
                        s.selected = null
                        s.selectedLabel = null
                        SamsungRecordingUi.refreshSelection(s)
                    }
                )
        }
    }

    @JvmStatic
    private fun refreshSelection(s: SamsungRecordingUi.State) {
        for (i in s.modes.indices) Reflect.callMethod(
            s.modes.get(i)!!,
            "setScreenButtonSelectedState",
            i == s.mode,
        )
        s.targetBox.setVisibility(if (s.mode >= 2) View.VISIBLE else View.GONE)
        s.summary.setText(s.strings.getString(R.string.selected_target, s.selectedLabel) + "  ›")
        s.summary.setContentDescription(
            s.strings.getString(R.string.change_target, s.selectedLabel)
        )
        SamsungRecordingUi.updateStart(s)
    }

    @JvmStatic
    private fun selectable(v: View) {
        val value = TypedValue()
        if (
            v.getContext()
                .getTheme()
                .resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        )
            v.setBackgroundResource(value.resourceId)
    }

    @JvmStatic
    private fun openPicker(
        rt: HookRuntime,
        s: SamsungRecordingUi.State,
        mode: Int,
        selectFull: Runnable,
    ) {
        if (s.picker != null || !rt.options!!.enabled || !rt.guard.allowed()) return
        try {
            val parent: Dialog? =
                Reflect.getObjectField(s.controller, "mSoundSettingSuggestionPopup") as Dialog?
            if (parent == null || !parent.isShowing()) return
            // Use the very same Samsung AlertDialog builder and theme as the recording start
            // dialog.
            val theme =
                s.context
                    .getResources()
                    .getIdentifier(
                        "RecorderDialogAlertTheme",
                        "style",
                        "com.samsung.android.app.smartcapture",
                    )
            val binding = HookCompatibility.samsungDialog(Build.VERSION.SDK_INT)
            val builder =
                Reflect.newInstance(
                    Reflect.findClass(
                        binding!!.builderClass!!,
                        s.controller.javaClass.getClassLoader(),
                    ),
                    s.context,
                    theme,
                )
            val params = Reflect.getObjectField(builder!!, "b")
            val themed: Context? = Reflect.getObjectField(params!!, "a") as Context?
            Reflect.setObjectField(
                params!!,
                "d",
                s.strings.getString(if (mode == 3) R.string.choose_window else R.string.choose_app),
            )
            Reflect.setObjectField(params!!, "i", s.strings.getString(R.string.back_to_recording))
            val content = LinearLayout(themed)
            content.setOrientation(LinearLayout.VERTICAL)
            content.setPadding(
                SamsungRecordingUi.dp(themed!!, 24),
                0,
                SamsungRecordingUi.dp(themed!!, 24),
                0,
            )
            s.pickerMode = mode
            s.query = ""
            s.allApps = false
            s.entries.clear()
            val search = EditText(themed)
            search.setHint(s.strings.getString(R.string.search_apps))
            search.setSingleLine(true)
            search.setTextSize(16f)
            search.setTypeface(s.summary.getTypeface())
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH)
            content.addView(
                search,
                android.widget.LinearLayout.LayoutParams(-1, SamsungRecordingUi.dp(themed!!, 56)),
            )
            val heading = LinearLayout(themed)
            heading.setGravity(Gravity.CENTER_VERTICAL)
            s.section = SamsungRecordingUi.nativeText(themed, s.modes.get(0))
            s.section!!.setTextSize(14f)
            heading.addView(
                s.section,
                android.widget.LinearLayout.LayoutParams(
                    0,
                    SamsungRecordingUi.dp(themed!!, 48),
                    1f,
                ),
            )
            s.section!!.setGravity(Gravity.CENTER_VERTICAL)
            s.other = SamsungRecordingUi.nativeText(themed, s.modes.get(0))
            s.other!!.setTextSize(14f)
            s.other!!.setGravity(Gravity.CENTER)
            s.other!!.setPadding(SamsungRecordingUi.dp(themed!!, 12), 0, 0, 0)
            s.other!!.setTextColor(Reflect.getIntField(s.modes.get(0)!!, "selectedColor"))
            SamsungRecordingUi.selectable(s!!.other!!)
            heading.addView(
                s.other,
                android.widget.LinearLayout.LayoutParams(-2, SamsungRecordingUi.dp(themed!!, 48)),
            )
            content.addView(heading)
            val listBox = FrameLayout(themed!!)
            s.rows = ListView(themed)
            s.rows!!.setDivider(null)
            s.rows!!.setClipToPadding(false)
            s.rows!!.setPadding(0, 0, 0, SamsungRecordingUi.dp(themed!!, 8))
            listBox.addView(s.rows, android.widget.FrameLayout.LayoutParams(-1, -1))
            s.message = SamsungRecordingUi.nativeText(themed, s.modes.get(0))
            s.message!!.setTextSize(14f)
            s.message!!.setGravity(Gravity.CENTER)
            s.message!!.setPadding(
                SamsungRecordingUi.dp(themed!!, 8),
                SamsungRecordingUi.dp(themed!!, 16),
                SamsungRecordingUi.dp(themed!!, 8),
                SamsungRecordingUi.dp(themed!!, 16),
            )
            listBox.addView(s.message, android.widget.FrameLayout.LayoutParams(-1, -1))
            s.rows!!.setEmptyView(s.message)
            content.addView(listBox, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            val available = themed!!.getResources().getDisplayMetrics().heightPixels
            val height =
                max(
                    SamsungRecordingUi.dp(themed!!, 180),
                    min(
                        SamsungRecordingUi.dp(themed!!, 520),
                        (available * .82f).toInt() - SamsungRecordingUi.dp(themed!!, 140),
                    ),
                )
            val frame = FrameLayout(themed!!)
            frame.addView(content, android.widget.FrameLayout.LayoutParams(-1, height))
            Reflect.setObjectField(params!!, "o", frame)
            val picker: Dialog? = Reflect.callMethod(builder!!, binding!!.createMethod!!) as Dialog?
            s.picker = picker
            val window = picker!!.getWindow()
            window!!.setType(parent.getWindow()!!.getAttributes().type)
            window!!.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            picker!!.setCanceledOnTouchOutside(false)
            val decor = parent.getWindow()!!.getDecorView()
            val parentAttached = booleanArrayOf(true)
            val lifecycle: OnAttachStateChangeListener =
                object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}

                    override fun onViewDetachedFromWindow(v: View) {
                        parentAttached[0] = false
                        picker!!.dismiss()
                    }
                }
            decor.addOnAttachStateChangeListener(lifecycle)
            picker!!.setOnDismissListener(
                OnDismissListener { d: DialogInterface? ->
                    decor.removeOnAttachStateChangeListener(lifecycle)
                    if (s.picker !== picker) return@OnDismissListener
                    s.picker = null
                    s.generation++
                    if (parentAttached[0] && decor.isAttachedToWindow()) parent.show()
                }
            )
            s.other!!.setOnClickListener(
                OnClickListener { v: View? ->
                    s.allApps = !s.allApps
                    SamsungRecordingUi.render(s, s.query, selectFull)
                }
            )
            search.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        x: CharSequence?,
                        a: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        x: CharSequence,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        SamsungRecordingUi.render(s, x.toString(), selectFull)
                    }

                    override fun afterTextChanged(e: Editable?) {}
                }
            )
            picker!!.show()
            // hide() keeps Samsung's cancellation/service lifecycle intact; dismiss() would abort
            // setup.
            parent.hide()
            SamsungRecordingUi.load(rt, s, selectFull)
        } catch (error: Throwable) {
            if (s.picker != null) s!!.picker!!.dismiss()
            rt.fail(error)
        }
    }

    @JvmStatic
    private fun listener(v: View?): OnClickListener? {
        val info = Reflect.callMethod(v!!, "getListenerInfo")
        val result: OnClickListener? =
            Reflect.getObjectField(info!!, "mOnClickListener") as OnClickListener?
        checkNotNull(result) { "Native recording-area listener missing" }
        return result
    }

    @JvmStatic
    private fun graphicalChoice(
        host: Context?,
        label: String?,
        model: View,
        window: Boolean,
    ): View? {
        val choice: View? = Reflect.newInstance(model.javaClass, host) as View?
        val modelImage: ImageView? = Reflect.getObjectField(model, "imageView") as ImageView?
        val image: ImageView? = Reflect.getObjectField(choice!!, "imageView") as ImageView?
        val text: TextView? = Reflect.getObjectField(choice!!, "textView") as TextView?
        text!!.setText(label)
        text!!.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            (Reflect.getObjectField(model, "textView") as TextView).getTextSize(),
        )
        val size = image!!.getLayoutParams()
        size.width = modelImage!!.getLayoutParams().width
        size.height = modelImage!!.getLayoutParams().height
        image!!.setLayoutParams(size)
        image!!.setPadding(0, 0, 0, 0)
        image!!.setImageDrawable(RecordingAreaGraphic(window))
        choice!!.setContentDescription(label)
        choice!!.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES)
        return choice
    }

    @JvmStatic
    private fun load(rt: HookRuntime?, s: SamsungRecordingUi.State, selectFull: Runnable) {
        val generation: Int = ++s.generation
        s.message!!.setText(s.strings.getString(R.string.loading_targets))
        s.rows!!.setAdapter(null)
        SamsungRecordingUi.updateHeading(s)
        val query = Bundle()
        query.putBoolean("windows", s.pickerMode == 3)
        val received = booleanArrayOf(false)
        SamsungRecordingUi.main.postDelayed(
            Runnable {
                if (!received[0] && generation == s.generation && s.picker != null)
                    s.message!!.setText(s.strings.getString(R.string.target_unavailable))
            },
            5000,
        )
        TargetBridge.request(
            rt!!,
            "list",
            query,
            object : ResultReceiver(SamsungRecordingUi.main) {
                override fun onReceiveResult(code: Int, result: Bundle?) {
                    if (generation != s.generation || s.picker == null) return
                    received[0] = true
                    if (code != Activity.RESULT_OK || result == null) {
                        s.message!!.setText(s.strings.getString(R.string.target_unavailable))
                        return
                    }
                    val entries =
                        result.getParcelableArrayList<Bundle?>("entries", Bundle::class.java)
                    s.entries.clear()
                    if (entries != null) s.entries.addAll(entries)
                    SamsungRecordingUi.render(s, s.query, selectFull)
                }
            },
        )
    }

    @JvmStatic
    private fun updateHeading(s: SamsungRecordingUi.State) {
        val search: Boolean = !s.query.isBlank()
        s.section!!.setText(
            s.strings.getString(
                if (s.pickerMode == 3) R.string.area_window
                else if (search) R.string.search_results
                else if (s.allApps) R.string.all_apps else R.string.recent_apps
            )
        )
        s.other!!.setVisibility(if (s.pickerMode == 3 || search) View.GONE else View.VISIBLE)
        s.other!!.setText(
            s.strings.getString(if (s.allApps) R.string.recent_apps else R.string.other_apps)
        )
    }

    @JvmStatic
    private fun render(s: SamsungRecordingUi.State, query: String, selectFull: Runnable) {
        s.query = query
        SamsungRecordingUi.updateHeading(s)
        val filtered = ArrayList<Bundle?>()
        val filter = query.trim().lowercase()
        val search: Boolean = !filter.isEmpty()
        val shownApps = HashSet<String?>()
        for (e in s.entries) {
            if (s.pickerMode == 2 && !shownApps.add(e!!.getString("package", ""))) continue
            if (s.pickerMode != 3 && !s.allApps && !search && e!!.getInt("recent_order", -1) < 0)
                continue
            if (
                ((e!!.getString("label", "") +
                        " " +
                        e!!.getString("detail", "") +
                        " " +
                        e!!.getString("package", "")))
                    .lowercase()
                    .contains(filter)
            )
                filtered.add(e)
        }
        if (s.allApps || search)
            filtered.sortWith(
                Comparator.comparing<Bundle?, String?>(
                    Function { b: Bundle? -> b!!.getString("label", "") },
                    Collator.getInstance(),
                )
            )
        s.message!!.setText(
            s.strings.getString(
                if (search) R.string.no_search_results
                else if (s.pickerMode == 3) R.string.no_targets
                else if (s.allApps) R.string.no_search_results else R.string.no_recent_apps
            )
        )
        s.rows!!.setAdapter(
            object : BaseAdapter() {
                override fun getCount(): Int {
                    return filtered.size
                }

                override fun getItem(position: Int): Any? {
                    return filtered.get(position)
                }

                override fun getItemId(position: Int): Long {
                    return position.toLong()
                }

                override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                    val entry = filtered.get(position)
                    val c = parent.getContext()
                    val row = LinearLayout(c)
                    row.setGravity(Gravity.CENTER_VERTICAL)
                    row.setMinimumHeight(SamsungRecordingUi.dp(c, 64))
                    row.setPadding(
                        SamsungRecordingUi.dp(c, 4),
                        SamsungRecordingUi.dp(c, 8),
                        SamsungRecordingUi.dp(c, 4),
                        SamsungRecordingUi.dp(c, 8),
                    )
                    SamsungRecordingUi.selectable(row)
                    val icon = ImageView(c)
                    try {
                        icon.setImageDrawable(
                            c.getPackageManager()
                                .getApplicationIcon(entry!!.getString("package", ""))
                        )
                    } catch (ignored: Exception) {}
                    row.addView(
                        icon,
                        android.widget.LinearLayout.LayoutParams(
                            SamsungRecordingUi.dp(c, 36),
                            SamsungRecordingUi.dp(c, 36),
                        ),
                    )
                    val text = SamsungRecordingUi.nativeText(c, s.modes.get(0))
                    val name = entry!!.getString("label", "")
                    val detail = entry!!.getString("detail", "")
                    text.setText(name + (if (detail.isEmpty()) "" else "\n" + detail))
                    text.setTextSize(16f)
                    text.setMaxLines(2)
                    text.setEllipsize(TruncateAt.END)
                    text.setPadding(SamsungRecordingUi.dp(c, 16), 0, SamsungRecordingUi.dp(c, 8), 0)
                    row.addView(text, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                    val radio = RadioButton(c)
                    val model: RadioButton? =
                        Reflect.getObjectField(s.modes.get(0)!!, "radioButton") as RadioButton?
                    radio.setButtonTintList(model!!.getButtonTintList())
                    radio.setChecked(
                        s.mode == s.pickerMode && s.selectedKey == entry!!.getString("key")
                    )
                    radio.setClickable(false)
                    radio.setFocusable(false)
                    radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                    row.addView(
                        radio,
                        android.widget.LinearLayout.LayoutParams(-2, SamsungRecordingUi.dp(c, 48)),
                    )
                    row.setOnClickListener(
                        OnClickListener { v: View? ->
                            selectFull.run()
                            s.mode = s.pickerMode
                            s.selected = entry!!.getString("id")
                            s.selectedLabel = name
                            s.selectedKey = entry!!.getString("key")
                            SamsungRecordingUi.refreshSelection(s)
                            if (s.picker != null) s!!.picker!!.dismiss()
                        }
                    )
                    return row
                }
            }
        )
    }

    @JvmStatic
    private fun nativeText(c: Context?, model: View?): TextView {
        val text = TextView(c)
        val original: TextView? = Reflect.getObjectField(model!!, "textView") as TextView?
        text.setTypeface(original!!.getTypeface())
        text.setTextColor((Reflect.getIntField(model!!, "unselectedColor")).toInt())
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, original!!.getTextSize())
        return text
    }

    @JvmStatic
    fun updateStart(s: SamsungRecordingUi.State) {
        try {
            val dialog: Dialog? =
                Reflect.getObjectField(s.controller, "mSoundSettingSuggestionPopup") as Dialog?
            if (dialog == null) return
            val start = dialog.findViewById<View?>(android.R.id.button1)
            if (start != null) start.setEnabled(s.mode < 2 || s.selected != null)
        } catch (ignored: Throwable) {}
    }

    class State(
        controller: Any,
        sourceRoot: View,
        context: Context,
        targetBox: LinearLayout,
        summary: TextView,
        modes: MutableList<View?>,
        strings: Context,
    ) {
        @JvmField val controller: Any
        @JvmField val sourceRoot: View
        @JvmField val context: Context
        @JvmField val targetBox: LinearLayout
        @JvmField val summary: TextView
        @JvmField var rows: ListView? = null
        @JvmField var message: TextView? = null
        @JvmField var section: TextView? = null
        @JvmField var other: TextView? = null
        @JvmField var picker: Dialog? = null
        @JvmField val modes: MutableList<View?>
        @JvmField val strings: Context
        @JvmField val entries: MutableList<Bundle?> = ArrayList<Bundle?>()
        @JvmField var selected: String? = null
        @JvmField var selectedLabel: String? = null
        @JvmField var selectedKey: String? = null
        @JvmField var allApps: Boolean = false
        @JvmField var pickerMode: Int = 0
        @JvmField var query: String = ""
        @JvmField var mode: Int = 0
        @JvmField var generation: Int = 0
        @JvmField var audioOnly: Boolean = false

        init {
            this.controller = controller
            this.sourceRoot = sourceRoot
            this.context = context
            this.targetBox = targetBox
            this.summary = summary
            this.modes = modes
            this.strings = strings
        }
    }
}
