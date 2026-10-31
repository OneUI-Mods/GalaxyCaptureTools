package dev.ryuya.galaxycapture

import android.app.Activity
import android.app.ActivityManager.RecentTaskInfo
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.WindowManager.LayoutParams
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.text.Collator
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.Map
import java.util.UUID
import java.util.function.Function
import java.util.function.ToIntFunction
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.math.log

/*
 * Privileged target catalog. Opaque choices expire, and are bound to the requesting recorder UID.
 */
object CaptureTargets {
    private val catalogs: MutableMap<Int?, MutableMap<String?, CaptureTargets.Candidate?>?> =
        HashMap<Int?, MutableMap<String?, CaptureTargets.Candidate?>?>()
    @Volatile private var selected: CaptureTargets.Selection? = null
    private var loader: ClassLoader? = null

    @JvmStatic
    fun install(rt: HookRuntime, classes: ClassLoader?) {
        CaptureTargets.loader = classes
        rt.context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val receiver =
                        intent.getParcelableExtra<ResultReceiver?>(
                            "receiver",
                            ResultReceiver::class.java,
                        )
                    if (receiver == null) return
                    val uid = getSentFromUid()
                    val packages = context.getPackageManager().getPackagesForUid(uid)
                    if (
                        packages == null ||
                            (!Arrays.asList<String?>(*packages)
                                .contains("com.samsung.android.app.smartcapture") &&
                                !Arrays.asList<String?>(*packages).contains("com.android.systemui"))
                    ) {
                        Hooks.log("GalaxyCapture target sender rejected; uid=" + uid)
                        receiver.send(Activity.RESULT_CANCELED, Bundle.EMPTY)
                        return
                    }
                    try {
                        check(!(!rt.guard.allowed() || !rt.options!!.enabled)) {
                            "Capture tools disabled"
                        }
                        val data = intent.getBundleExtra("data")
                        val operation = intent.getStringExtra("operation")
                        val result: Bundle? =
                            when (if (operation == null) "" else operation) {
                                "list" ->
                                    CaptureTargets.catalog(
                                        rt,
                                        uid,
                                        data != null && data.getBoolean("windows", false),
                                    )
                                "activate" -> CaptureTargets.activate(rt, uid, data!!)
                                "audio_uid" -> CaptureTargets.audioUid(uid)
                                "health" -> Bundle.EMPTY
                                else -> throw IllegalArgumentException("Unknown target operation")
                            }
                        receiver.send(Activity.RESULT_OK, result)
                    } catch (error: Throwable) {
                        Hooks.log("GalaxyCapture target request failed: " + error)
                        Hooks.log(error)
                        val result = Bundle()
                        result.putString("error", "Target unavailable")
                        receiver.send(Activity.RESULT_CANCELED, result)
                    }
                }
            },
            IntentFilter(TargetBridge.ACTION),
            "android.permission.MANAGE_MEDIA_PROJECTION",
            rt.worker,
            Context.RECEIVER_EXPORTED,
        )
    }

    @JvmStatic
    private fun wm(): Any? {
        val sm = Reflect.findClass("android.os.ServiceManager", null)
        return Reflect.callStaticMethod(sm, "getService", "window")
    }

    @JvmStatic
    private fun allowedWindow(w: Any?): Boolean {
        if (w == null || !(FrameworkHooks.call(w, "isAttached") as Boolean)) return false
        val animator = FrameworkHooks.field(w, "mWinAnimator")
        if (!Reflect.getBooleanField(animator!!, "mSurfaceShown")) return false
        val type = Reflect.getIntField(FrameworkHooks.field(w, "mAttrs")!!, "type")
        return type >= 1 &&
            type < 2000 &&
            ((FrameworkHooks.runtime != null &&
                FrameworkHooks.runtime!!.options!!.enabled &&
                FrameworkHooks.runtime!!.options!!.secureRecording) ||
                (Reflect.getIntField(FrameworkHooks.field(w, "mAttrs")!!, "flags") and 8192) == 0)
    }

    @JvmStatic
    private fun visit(
        node: Any?,
        tasks: MutableList<Any?>,
        windows: MutableList<Any?>,
        seen: MutableSet<Any?>,
    ) {
        if (node == null || seen.size > 4096 || !seen.add(node)) return
        val name = node.javaClass.getName()
        if (name == "com.android.server.wm.Task") tasks.add(node)
        if (name == "com.android.server.wm.WindowState" && CaptureTargets.allowedWindow(node))
            windows.add(node)
        for (child in
            ArrayList<Any?>(
                FrameworkHooks.field(node, "mChildren") as MutableList<*>?
            )) CaptureTargets.visit(child, tasks, windows, seen)
    }

    @JvmStatic
    private fun catalog(rt: HookRuntime, owner: Int, windows: Boolean): Bundle {
        val wm = CaptureTargets.wm()
        val tasks = ArrayList<Any?>()
        val visible = ArrayList<Any?>()
        val result = ArrayList<CaptureTargets.Candidate?>()
        val recents: MutableList<RecentTaskInfo?>?
        val recentOrder = HashMap<Int?, Int?>()
        val expires: Long = SystemClock.elapsedRealtime() + 300000
        synchronized(FrameworkHooks.field(wm, "mGlobalLock")!!) {
            val recentTasks =
                FrameworkHooks.field(FrameworkHooks.field(wm, "mAtmService"), "mRecentTasks")
            // Native Recents filtering excludes home, unavailable tasks and other users.
            recents =
                ArrayList<RecentTaskInfo?>(
                    FrameworkHooks.call(
                        recentTasks,
                        "getRecentTasksImpl",
                        100,
                        2,
                        owner / 100000,
                        owner,
                        true,
                    ) as MutableList<RecentTaskInfo?>?
                )
            for (i in recents.indices) recentOrder.put(recents.get(i)!!.taskId, i)
            val root = FrameworkHooks.field(wm, "mRoot")
            val dc = FrameworkHooks.call(root, "getDisplayContent", 0)
            CaptureTargets.visit(
                dc,
                tasks,
                visible,
                Collections.newSetFromMap<Any?>(IdentityHashMap<Any?, Boolean?>()),
            )
            if (windows) {
                for (w in visible) {
                    val activity = FrameworkHooks.field(w, "mActivityRecord")
                    if (activity == null) continue
                    val task = FrameworkHooks.call(activity, "getTask")
                    if (task == null) continue
                    val info: ActivityInfo? =
                        FrameworkHooks.field(activity, "info") as ActivityInfo?
                    if (info!!.applicationInfo.uid / 100000 != owner / 100000) continue
                    val title =
                        (FrameworkHooks.field(w, "mAttrs") as LayoutParams).getTitle().toString()
                    result.add(
                        CaptureTargets.Candidate(
                            UUID.randomUUID().toString(),
                            info!!.applicationInfo.uid,
                            Reflect.getIntField(task, "mTaskId"),
                            info!!.packageName,
                            info!!.name,
                            title,
                            recentOrder.getOrDefault(Reflect.getIntField(task, "mTaskId"), -1)!!,
                            w,
                            activity,
                            expires,
                        )
                    )
                }
            }
        }
        val pm = rt.context.getPackageManager()
        if (!windows) {
            for (i in recents!!.indices) {
                val task = recents!!.get(i)
                if (Reflect.getIntField(task!!, "userId") != owner / 100000) continue
                var component: ComponentName? =
                    if (task!!.topActivity != null) task!!.topActivity else task!!.baseActivity
                if (component == null && task!!.baseIntent != null)
                    component = task!!.baseIntent.getComponent()
                if (component == null) continue
                try {
                    val info = pm.getApplicationInfo(component.getPackageName(), 0)
                    result.add(
                        CaptureTargets.Candidate(
                            UUID.randomUUID().toString(),
                            info.uid,
                            task!!.taskId,
                            component.getPackageName(),
                            component.getClassName(),
                            "",
                            i,
                            null,
                            null,
                            expires,
                        )
                    )
                } catch (ignored: NameNotFoundException) {}
            }
        }
        val running = HashSet<String?>()
        for (c in result) running.add(c!!.pkg)
        if (!windows) {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(launch, 0)) {
                val info = r.activityInfo
                if (info == null || !info.exported || !running.add(info.packageName)) continue
                result.add(
                    CaptureTargets.Candidate(
                        UUID.randomUUID().toString(),
                        info.applicationInfo.uid,
                        -1,
                        info.packageName,
                        info.name,
                        "",
                        -1,
                        null,
                        null,
                        expires,
                    )
                )
            }
        }
        val entries = ArrayList<Bundle?>()
        val cache = HashMap<String?, CaptureTargets.Candidate?>()
        for (c in result) {
            try {
                val info = pm.getApplicationInfo(c!!.pkg!!, 0)
                val entry = Bundle()
                val app = info.loadLabel(pm).toString()
                var detail: String? = if (windows) c!!.detail else ""
                if (detail!!.contains("/"))
                    detail = detail!!.substring(detail!!.lastIndexOf('/') + 1)
                entry.putString("id", c!!.id)
                entry.putString("label", app)
                entry.putString("detail", detail)
                entry.putString("package", c!!.pkg)
                entry.putInt("uid", c!!.uid)
                entry.putInt("recent_order", c!!.recentOrder)
                entry.putString(
                    "key",
                    c!!.pkg +
                        ":" +
                        c!!.task +
                        ":" +
                        (if (windows) System.identityHashCode(c!!.window) else "app"),
                )
                entries.add(entry)
                cache.put(c!!.id, c)
            } catch (ignored: NameNotFoundException) {}
        }
        entries.sortWith(
            Comparator.comparingInt<Bundle?>(
                    ToIntFunction { b: Bundle? ->
                        if (b!!.getInt("recent_order", -1) < 0) Int.MAX_VALUE
                        else b!!.getInt("recent_order")
                    }
                )
                .thenComparing<String?>(
                    Function { b: Bundle? -> b!!.getString("label", "") },
                    Collator.getInstance(),
                )
        )
        // Reopening/cancelling the picker must not invalidate the previously confirmed choice.
        val retained =
            CaptureTargets.catalogs.computeIfAbsent(owner) { ignored: Int? ->
                HashMap<String?, CaptureTargets.Candidate?>()
            }
        retained!!.values.removeIf { c: CaptureTargets.Candidate? ->
            c!!.expires < SystemClock.elapsedRealtime()
        }
        retained!!.putAll(cache)
        rt.worker.postDelayed(
            Runnable {
                val current = CaptureTargets.catalogs.get(owner)
                if (current == null) return@Runnable
                current.values.removeIf { c: CaptureTargets.Candidate? ->
                    c!!.expires <= SystemClock.elapsedRealtime()
                }
                if (current.isEmpty()) CaptureTargets.catalogs.remove(owner)
            },
            300000,
        )
        val out = Bundle()
        out.putParcelableArrayList("entries", entries)
        return out
    }

    @JvmStatic
    private fun activate(rt: HookRuntime, owner: Int, data: Bundle?): Bundle {
        requireNotNull(data)
        val c =
            CaptureTargets.catalogs
                .getOrDefault(owner, Map.of<String?, CaptureTargets.Candidate?>())!!
                .get(data.getString("id", ""))
        check(!(c == null || c.expires < SystemClock.elapsedRealtime())) { "Expired target" }
        val strict = data.getBoolean("windows", false)
        check(!(strict && (c.window == null || !rt.options!!.singleWindow))) {
            "Window selection disabled"
        }
        val cookieClass = Reflect.findClass("android.app.ActivityOptions\$LaunchCookie", null)
        val cookie = Reflect.newInstance(cookieClass, "galaxy_capture_target")
        val binder: IBinder? = FrameworkHooks.field(cookie, "binder") as IBinder?
        if (c.task >= 0) {
            val manager =
                Reflect.callStaticMethod(
                    Reflect.findClass("android.app.ActivityTaskManager", null),
                    "getService",
                )
            FrameworkHooks.call(manager, "startActivityFromRecents", c.task, null)
        } else {
            val launch = rt.context.getPackageManager().getLaunchIntentForPackage(c.pkg!!)
            checkNotNull(launch) { "App has no launcher" }
            val options = ActivityOptions.makeBasic()
            FrameworkHooks.call(options, "setLaunchCookie", cookie)
            rt.context.startActivity(
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                FrameworkHooks.call(options, "toBundle") as Bundle?,
            )
        }
        CaptureTargets.selected =
            CaptureTargets.Selection(
                owner,
                c.task,
                c.window,
                c.activity,
                strict,
                SystemClock.elapsedRealtime() + 60000,
            )
        val pending = CaptureTargets.selected
        rt.worker.postDelayed(
            Runnable {
                if (CaptureTargets.selected === pending) CaptureTargets.selected = null
            },
            60000,
        )
        val out = Bundle()
        out.putBinder("cookie", binder)
        out.putInt("task_id", c.task)
        out.putInt("uid", c.uid)
        return out
    }

    @JvmStatic
    fun take(owner: Int, task: Int): CaptureTargets.Selection? {
        val choice = CaptureTargets.selected
        if (
            choice != null &&
                choice.owner == owner &&
                choice.expires >= SystemClock.elapsedRealtime() &&
                (choice.task < 0 || choice.task == task)
        ) {
            CaptureTargets.selected = null
            return choice
        }
        return null
    }

    @JvmStatic
    private fun audioUid(owner: Int): Bundle {
        val wm = CaptureTargets.wm()
        var uid: Int = -1
        var app: Boolean = false
        synchronized(FrameworkHooks.field(wm, "mGlobalLock")!!) {
            val session =
                FrameworkHooks.call(
                    FrameworkHooks.field(wm, "mContentRecordingController"),
                    "getContentRecordingSessionLocked",
                )
            if (session != null && FrameworkHooks.call(session, "getContentToRecord") as Int == 1) {
                val dc =
                    FrameworkHooks.call(
                        FrameworkHooks.field(wm, "mRoot"),
                        "getDisplayContent",
                        FrameworkHooks.call(session, "getVirtualDisplayId") as Int,
                    )
                if (
                    dc != null &&
                        Reflect.getIntField(
                            FrameworkHooks.field(dc, "mDisplayInfo")!!,
                            "ownerUid",
                        ) == owner
                ) {
                    app = true
                    uid = FrameworkHooks.call(session, "getTargetUid") as Int
                }
            }
        }
        val out = Bundle()
        out.putInt("uid", uid)
        out.putBoolean("app", app)
        return out
    }

    @JvmRecord
    private data class Candidate(
        val id: String?,
        val uid: Int,
        val task: Int,
        val pkg: String?,
        val label: String?,
        val detail: String?,
        val recentOrder: Int,
        val window: Any?,
        val activity: Any?,
        val expires: Long,
    ) {
        override fun toString(): String =
            "Candidate[id=$id, uid=$uid, task=$task, pkg=$pkg, label=$label, detail=$detail, recentOrder=$recentOrder, window=$window, activity=$activity, expires=$expires]"
    }

    @JvmRecord
    data class Selection(
        val owner: Int,
        val task: Int,
        val window: Any?,
        val activity: Any?,
        val strict: Boolean,
        val expires: Long,
    ) {
        override fun toString(): String =
            "Selection[owner=$owner, task=$task, window=$window, activity=$activity, strict=$strict, expires=$expires]"
    }
}
