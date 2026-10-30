package dev.ryuya.galaxycapture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout.LayoutParams
import android.window.OnBackInvokedCallback
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.HashMap
import java.util.LinkedHashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Custom pages reuse Samsung's preference surface, floating toolbar, insets and rounding. */
object SamsungSettingsUi {
    private const val STATE: String = "galaxycapture.settings"
    private const val KEY: String = "galaxycapture."
    private val ACTIVITY_STATE: String = SamsungSettingsUi.STATE + ".activity"

    @JvmStatic
    fun install(rt: HookRuntime?, loader: ClassLoader) {
        val fragment =
            Reflect.findClass("com.samsung.android.app.settings.SettingsFragment", loader)
        Hooks.hookAllMethods(
            Reflect.findClass("androidx.preference.Preference", loader),
            "onBindViewHolder",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val state: SamsungSettingsUi.State? =
                        Reflect.getAdditionalInstanceField(p.thisObject, SamsungSettingsUi.STATE)
                            as SamsungSettingsUi.State?
                    if (state != null && state.active) state.bindMaster(p!!.args!![0])
                }
            },
        )
        val activity =
            Reflect.findClass("com.samsung.android.app.settings.SettingsActivity", loader)
        Hooks.hookAllMethods(
            activity,
            "onOptionsItemSelected",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state: SamsungSettingsUi.State? =
                        Reflect.getAdditionalInstanceField(
                            p.thisObject,
                            SamsungSettingsUi.ACTIVITY_STATE,
                        ) as SamsungSettingsUi.State?
                    if (
                        state != null &&
                            state.active &&
                            (p!!.args!![0] as MenuItem).getItemId() == android.R.id.home
                    ) {
                        state.back()
                        p.setResult(true)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            fragment,
            "onCreate",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.hasThrowable()) return
                    try {
                        val state = SamsungSettingsUi.State(loader, p.thisObject!!)
                        Reflect.setAdditionalInstanceField(
                            p.thisObject,
                            SamsungSettingsUi.STATE,
                            state,
                        )
                        state.addEntry()
                    } catch (e: Throwable) {
                        Hooks.log("GalaxyCapture settings unavailable: " + e)
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            fragment,
            "onConfigurationChanged",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val state: SamsungSettingsUi.State? =
                        Reflect.getAdditionalInstanceField(p.thisObject, SamsungSettingsUi.STATE)
                            as SamsungSettingsUi.State?
                    if (state != null && state.active) {
                        state.switchBar = null
                        state.switchRow = null
                        val adapter = Reflect.callMethod(state.list!!, "getAdapter")
                        if (adapter != null) Reflect.callMethod(adapter, "notifyDataSetChanged")
                    }
                }
            },
        )
        Hooks.hookAllMethods(
            fragment,
            "onResume",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val state: SamsungSettingsUi.State? =
                        Reflect.getAdditionalInstanceField(p.thisObject, SamsungSettingsUi.STATE)
                            as SamsungSettingsUi.State?
                    if (state != null) state.resume()
                }
            },
        )
        Hooks.hookAllMethods(
            fragment,
            "onPause",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state: SamsungSettingsUi.State? =
                        Reflect.getAdditionalInstanceField(p.thisObject, SamsungSettingsUi.STATE)
                            as SamsungSettingsUi.State?
                    if (state != null) state.pause()
                }
            },
        )
        Hooks.hookAllMethods(
            fragment,
            "onDestroy",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val state: SamsungSettingsUi.State? =
                        Reflect.getAdditionalInstanceField(p.thisObject, SamsungSettingsUi.STATE)
                            as SamsungSettingsUi.State?
                    if (state != null) {
                        state.pause()
                        state.destroy()
                    }
                }
            },
        )
    }

    private class State(loader: ClassLoader, fragment: Any) {
        @JvmField val loader: ClassLoader
        @JvmField val fragment: Any
        @JvmField val host: Context
        @JvmField val strings: Context
        @JvmField val preferences: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        @JvmField val screens: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        @JvmField var registered: Boolean = false
        @JvmField var master: Boolean = false
        @JvmField var active: Boolean = false
        @JvmField var manager: Any? = null
        @JvmField var originalScreen: Any? = null
        @JvmField var list: View? = null
        @JvmField var switchBar: View? = null
        @JvmField var switchRow: View? = null
        @JvmField var originalTitle: CharSequence? = null
        @JvmField var originalListState: Parcelable? = null
        @JvmField val backCallback: OnBackInvokedCallback = OnBackInvokedCallback { this.back() }
        @JvmField var currentPage: String = "home"
        @JvmField
        val updates: BroadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    refresh()
                }
            }

        init {
            this.loader = loader
            this.fragment = fragment
            host = Reflect.callMethod(fragment, "getContext") as Context
            strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        }

        fun addEntry() {
            if (
                Reflect.callMethod(fragment, "findPreference", SamsungSettingsUi.KEY + "entry") !=
                    null
            )
                return
            val group = make("PreferenceCategory", "category", R.string.app_name)
            var last = Reflect.getObjectField(fragment, "mScreenRecorderFolderPickerPreference")
            if (last == null) last = Reflect.getObjectField(fragment, "mScreenRecorder")
            if (last == null) return
            Reflect.callMethod(
                group!!,
                "setOrder",
                (Reflect.callMethod(last, "getOrder") as Number).toInt() + 1,
            )
            Reflect.callMethod(Reflect.callMethod(fragment, "getPreferenceScreen")!!, "f", group)
            val entry = make("Preference", "entry", R.string.custom_settings_entry)
            Reflect.callMethod(
                entry!!,
                "setSummary",
                strings.getText(R.string.custom_settings_summary),
            )
            click(entry, Runnable { this.open() })
            Reflect.callMethod(group!!, "f", entry)
        }

        fun make(type: String?, key: String?, title: Int): Any? {
            val pref =
                Reflect.newInstance(
                    Reflect.findClass("androidx.preference." + type, loader),
                    host,
                    null,
                )
            Reflect.callMethod(pref!!, "setKey", SamsungSettingsUi.KEY + key)
            Reflect.callMethod(pref!!, "setTitle", strings.getText(title))
            Reflect.callMethod(pref!!, "setPersistent", false)
            return pref
        }

        fun screen(name: String?): Any? {
            val screen = make("PreferenceScreen", "screen." + name, R.string.custom_settings_entry)
            Reflect.callMethod(screen!!, "onAttachedToHierarchy", manager)
            screens.put(name, screen)
            val master = make("Preference", "master." + name, R.string.enabled)
            Reflect.callMethod(
                master!!,
                "setLayoutResource",
                resource("layout", "settings_navigation_height_empty_preference"),
            )
            Reflect.callMethod(master!!, "setSelectable", false)
            Reflect.setAdditionalInstanceField(master, SamsungSettingsUi.STATE, this)
            Reflect.callMethod(screen!!, "f", master)
            val group = make("PreferenceCategory", "group." + name, R.string.custom_settings_entry)
            Reflect.callMethod(group!!, "setTitle", "")
            Reflect.callMethod(screen!!, "f", group)
            return group
        }

        fun click(pref: Any?, action: Runnable) {
            val callback =
                Reflect.findClass(
                    "androidx.preference.Preference\$OnPreferenceClickListener",
                    loader,
                )
            val listener =
                HostCallbacks.create(
                    loader,
                    callback,
                    InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                        action.run()
                        true
                    },
                )
            Reflect.callMethod(pref!!, "setOnPreferenceClickListener", listener)
        }

        fun buildPages() {
            manager = Reflect.callMethod(fragment, "getPreferenceManager")
            val home = screen("home")
            val groups =
                arrayOf<Array<String?>?>(
                    arrayOf<String?>(
                        "video",
                        "rotation",
                        "ignore_half_turns",
                        "single_window",
                        "secure_recording",
                    ),
                    arrayOf<String?>(
                        "audio",
                        "app_audio_only",
                        "include_call_audio",
                        "speaker_media",
                        "recording_volume",
                        "media_volume",
                        "mic_volume",
                    ),
                    arrayOf<String?>(
                        "controls",
                        "samsung_app",
                        "countdown",
                        "hide_duplicate_indicator",
                        "recording_icon_right",
                        "hide_recording_controls",
                    ),
                    arrayOf<String?>(
                        "screenshots",
                        "secure_screenshot",
                        "exclude_capture_controls",
                    ),
                )
            val titles =
                intArrayOf(
                    R.string.custom_video,
                    R.string.section_audio,
                    R.string.custom_controls,
                    R.string.section_screenshot,
                )
            val summaries =
                intArrayOf(
                    R.string.custom_video_detail,
                    R.string.custom_audio_detail,
                    R.string.custom_controls_detail,
                    R.string.custom_screenshot_detail,
                )
            val toggles = HashMap<String?, CaptureSettings.Toggle?>()
            for (toggle in CaptureSettings.all()) toggles.put(toggle!!.key, toggle)
            val listener =
                Reflect.findClass(
                    "androidx.preference.Preference\$OnPreferenceChangeListener",
                    loader,
                )
            val callback =
                HostCallbacks.create(
                    loader,
                    listener,
                    InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                        val key =
                            (Reflect.callMethod(args!![0]!!, "getKey") as String).substring(
                                SamsungSettingsUi.KEY.length
                            )
                        val update = Bundle()
                        if (args!![1] is Boolean) update.putBoolean(key, args[1] as Boolean)
                        else update.putInt(key, AudioLevels.clamp(args!![1] as Int))
                        save(update)
                    },
                )
            for (n in groups.indices) {
                val group: Array<String?>? = groups[n]
                val page: String? = group!![0]
                val navigation = make("Preference", "nav." + page, titles[n])
                Reflect.callMethod(navigation!!, "setSummary", strings.getText(summaries[n]))
                click(navigation, Runnable { showPage(page!!) })
                Reflect.callMethod(home!!, "f", navigation)
                val screen = screen(page)
                for (i in 1 until group!!.size) {
                    val key: String? = group!![i]
                    val toggle = toggles.get(key)
                    val pref: Any? =
                        if (toggle == null)
                            make(
                                "SeekBarPreference",
                                key,
                                if (key == "media_volume") R.string.media_recording_volume
                                else R.string.mic_recording_volume,
                            )
                        else make("SwitchPreferenceCompat", key, toggle.title)
                    if (toggle != null)
                        Reflect.callMethod(pref!!, "setSummary", strings.getText(toggle.detail))
                    Reflect.callMethod(pref!!, "setOnPreferenceChangeListener", callback)
                    preferences.put(key, pref)
                    Reflect.callMethod(screen!!, "f", pref)
                }
            }
        }

        fun resource(type: String?, name: String?): Int {
            val id = host.getResources().getIdentifier(name, type, host.getPackageName())
            check(id != 0) { "Samsung resource unavailable: " + type + "/" + name }
            return id
        }

        fun open() {
            if (active) return
            try {
                if (screens.isEmpty()) buildPages()
                val activity: Activity? = host as Activity?
                originalScreen = Reflect.callMethod(fragment, "getPreferenceScreen")
                originalTitle = activity!!.getTitle()
                list = Reflect.callMethod(fragment, "getListView") as View?
                originalListState =
                    Reflect.callMethod(
                        Reflect.callMethod(list!!, "getLayoutManager")!!,
                        "onSaveInstanceState",
                    ) as Parcelable?
                active = true
                Reflect.setAdditionalInstanceField(activity, SamsungSettingsUi.ACTIVITY_STATE, this)
                activity!!
                    .getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(1, backCallback)
                showPage("home")
            } catch (e: Throwable) {
                Hooks.log("GalaxyCapture custom settings: " + e)
                close()
            }
        }

        fun bindMaster(holder: Any?) {
            try {
                if (switchRow == null) {
                    val nativeSubPage =
                        (host as Activity)
                            .getLayoutInflater()
                            .inflate(resource("layout", "settings_sub_layout"), null)
                    switchBar = nativeSubPage.findViewById<View?>(resource("id", "switch_bar"))
                    checkNotNull(switchBar) { "Samsung switch bar unavailable" }
                    switchRow = switchBar!!.getParent() as View?
                    (switchRow!!.getParent() as ViewGroup).removeView(switchRow)
                    Reflect.callMethod(
                        switchBar!!,
                        "setSessionDescription",
                        strings.getString(R.string.enabled),
                    )
                    val listener =
                        Reflect.findClass(
                            "androidx.appcompat.widget.SeslSwitchBar\$OnSwitchChangeListener",
                            loader,
                        )
                    Reflect.callMethod(
                        switchBar!!,
                        "c",
                        HostCallbacks.create(
                            loader,
                            listener,
                            InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                                val update = Bundle()
                                update.putBoolean("enabled", args!![1] as Boolean)
                                if (!save(update)) refresh()
                                null
                            },
                        ),
                    )
                }
                val item: ViewGroup? = Reflect.getObjectField(holder!!, "itemView") as ViewGroup?
                if (switchRow!!.getParent() !== item) {
                    if (switchRow!!.getParent() != null)
                        (switchRow!!.getParent() as ViewGroup).removeView(switchRow)
                    item!!.removeAllViews()
                    item!!.addView(switchRow, LayoutParams(-1, -2))
                }
                Reflect.callMethod(switchBar!!, "setCheckedInternal", master)
            } catch (e: Throwable) {
                Hooks.log("GalaxyCapture switch bar: " + e)
            }
        }

        fun title(title: CharSequence?) {
            val activity: Activity? = host as Activity?
            activity!!.setTitle(title)
            val actionBar = Reflect.callMethod(activity, "getSupportActionBar")
            if (actionBar != null) Reflect.callMethod(actionBar, "p", title)
            val collapsing = activity!!.findViewById<View?>(resource("id", "collapsing_toolbar"))
            if (collapsing != null) Reflect.callMethod(collapsing, "setTitle", title)
            val toolbar = activity!!.findViewById<View?>(resource("id", "toolbar"))
            if (toolbar != null) Reflect.callMethod(toolbar, "setTitle", title)
            val appBar = activity!!.findViewById<View?>(resource("id", "app_bar"))
            if (appBar != null) Reflect.callMethod(appBar, "setExpanded", true)
        }

        fun showPage(name: String) {
            if (!active) return
            currentPage = name
            val title: Int =
                when (name) {
                    "video" -> R.string.custom_video
                    "audio" -> R.string.section_audio
                    "controls" -> R.string.custom_controls
                    "screenshots" -> R.string.section_screenshot
                    else -> R.string.custom_settings_entry
                }
            title(strings.getText(title))
            Reflect.callMethod(fragment, "setPreferenceScreen", screens.get(name))
            // The original list retains Samsung's divider, rounded corners, horizontal fill,
            // floating-toolbar linkage, scroll behaviour and accessibility semantics.
            list!!.post(Runnable { Reflect.callMethod(list!!, "scrollToPosition", 0) })
            refresh()
        }

        fun back() {
            if (currentPage == "home") close() else showPage("home")
        }

        fun close() {
            if (active) {
                active = false
                (host as Activity)
                    .getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(backCallback)
                Reflect.setAdditionalInstanceField(host, SamsungSettingsUi.ACTIVITY_STATE, null)
                Reflect.callMethod(fragment, "setPreferenceScreen", originalScreen)
                title(originalTitle)
                Reflect.callMethod(
                    (host as Activity).findViewById<View?>(resource("id", "app_bar")),
                    "setExpanded",
                    false,
                )
                val nativeList: View? = list
                val savedPosition: Parcelable? = originalListState
                nativeList!!.post(
                    Runnable {
                        Reflect.callMethod(
                            Reflect.callMethod(nativeList, "getLayoutManager")!!,
                            "onRestoreInstanceState",
                            savedPosition,
                        )
                    }
                )
            }
            switchBar = null
            switchRow = null
            list = null
        }

        fun destroy() {
            if (active)
                (host as Activity)
                    .getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(backCallback)
            active = false
            Reflect.setAdditionalInstanceField(host, SamsungSettingsUi.ACTIVITY_STATE, null)
            // The fragment view is already destroyed here: never schedule a new preference bind.
            switchBar = null
            switchRow = null
            list = null
        }

        fun save(update: Bundle?): Boolean {
            try {
                host
                    .getContentResolver()
                    .call(ConfigProvider.URI!!, "native_settings", null, update)
                return true
            } catch (e: Throwable) {
                Hooks.log("GalaxyCapture settings save: " + e)
                return false
            }
        }

        fun refresh() {
            try {
                val b = host.getContentResolver().call(ConfigProvider.URI!!, "config", null, null)
                if (b == null) return
                master = b.getBoolean("enabled", false)
                if (switchBar != null) Reflect.callMethod(switchBar!!, "setCheckedInternal", master)
                for (toggle in CaptureSettings.all()) {
                    val pref = preferences.get(toggle!!.key)
                    if (pref == null) continue
                    Reflect.callMethod(
                        pref,
                        "setChecked",
                        b.getBoolean(toggle!!.key, toggle!!.defaultValue),
                    )
                    Reflect.callMethod(pref, "setEnabled", master)
                }
                for (key in arrayOf<String>("media_volume", "mic_volume")) {
                    val pref = preferences.get(key)
                    if (pref == null) continue
                    val value = AudioLevels.clamp(b.getInt(key, 100))
                    Reflect.callMethod(pref, "g", value, true)
                    Reflect.callMethod(pref, "setSummary", value.toString() + "%")
                    Reflect.callMethod(
                        pref,
                        "setEnabled",
                        master && b.getBoolean("recording_volume", false),
                    )
                }
            } catch (e: Throwable) {
                Hooks.log("GalaxyCapture settings refresh: " + e)
            }
        }

        fun resume() {
            if (!registered) {
                host.registerReceiver(
                    updates,
                    IntentFilter(HookRuntime.CONFIG_CHANGED),
                    ConfigProvider.PERMISSION,
                    Handler(Looper.getMainLooper()),
                    Context.RECEIVER_EXPORTED,
                )
                registered = true
            }
            refresh()
        }

        fun pause() {
            if (registered) host.unregisterReceiver(updates)
            registered = false
        }
    }
}
