package dev.ryuya.galaxycapture;

import static dev.ryuya.galaxycapture.FrameworkHooks.*;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import de.robv.android.xposed.XposedHelpers;
import java.util.*;

/**
 * Privileged target catalog. Opaque choices expire, and are bound to the requesting recorder UID.
 */
final class CaptureTargets {
  private record Candidate(
      String id,
      int uid,
      int task,
      String pkg,
      String label,
      String detail,
      Object window,
      Object activity,
      long expires) {}

  record Selection(
      int owner, int task, Object window, Object activity, boolean strict, long expires) {}

  private static final Map<Integer, Map<String, Candidate>> catalogs = new HashMap<>();
  private static volatile Selection selected;
  private static ClassLoader loader;

  static void install(HookRuntime rt, ClassLoader classes) {
    loader = classes;
    rt.context.registerReceiver(
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            ResultReceiver receiver = intent.getParcelableExtra("receiver", ResultReceiver.class);
            if (receiver == null) return;
            int uid = getSentFromUid();
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null
                || (!Arrays.asList(packages).contains("com.samsung.android.app.smartcapture")
                    && !Arrays.asList(packages).contains("com.android.systemui"))) {
              de.robv.android.xposed.XposedBridge.log(
                  "GalaxyCapture target sender rejected; uid=" + uid);
              receiver.send(Activity.RESULT_CANCELED, Bundle.EMPTY);
              return;
            }
            try {
              if (!rt.guard.allowed() || !rt.options.enabled())
                throw new IllegalStateException("Capture tools disabled");
              Bundle data = intent.getBundleExtra("data");
              String operation = intent.getStringExtra("operation");
              Bundle result =
                  switch (operation == null ? "" : operation) {
                    case "list" ->
                        catalog(rt, uid, data != null && data.getBoolean("windows", false));
                    case "activate" -> activate(rt, uid, data);
                    case "audio_uid" -> audioUid(uid);
                    case "health" -> Bundle.EMPTY;
                    default -> throw new IllegalArgumentException("Unknown target operation");
                  };
              receiver.send(Activity.RESULT_OK, result);
            } catch (Throwable error) {
              de.robv.android.xposed.XposedBridge.log(
                  "GalaxyCapture target request failed: " + error);
              de.robv.android.xposed.XposedBridge.log(error);
              Bundle result = new Bundle();
              result.putString("error", "Target unavailable");
              receiver.send(Activity.RESULT_CANCELED, result);
            }
          }
        },
        new IntentFilter(TargetBridge.ACTION),
        "android.permission.MANAGE_MEDIA_PROJECTION",
        rt.worker,
        Context.RECEIVER_EXPORTED);
  }

  private static Object wm() {
    Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", null);
    return XposedHelpers.callStaticMethod(sm, "getService", "window");
  }

  private static boolean allowedWindow(Object w) {
    if (w == null || !(boolean) call(w, "isAttached")) return false;
    Object animator = field(w, "mWinAnimator");
    if (!XposedHelpers.getBooleanField(animator, "mSurfaceShown")) return false;
    int type = XposedHelpers.getIntField(field(w, "mAttrs"), "type");
    return type >= 1
        && type < 2000
        && (XposedHelpers.getIntField(field(w, "mAttrs"), "flags") & 8192) == 0;
  }

  private static void visit(
      Object node, List<Object> tasks, List<Object> windows, Set<Object> seen) {
    if (node == null || seen.size() > 4096 || !seen.add(node)) return;
    String name = node.getClass().getName();
    if (name.equals("com.android.server.wm.Task")) tasks.add(node);
    if (name.equals("com.android.server.wm.WindowState") && allowedWindow(node)) windows.add(node);
    for (Object child : new ArrayList<>((List<?>) field(node, "mChildren")))
      visit(child, tasks, windows, seen);
  }

  private static Bundle catalog(HookRuntime rt, int owner, boolean windows) {
    Object wm = wm();
    List<Object> tasks = new ArrayList<>(), visible = new ArrayList<>();
    List<Candidate> result = new ArrayList<>();
    long expires = SystemClock.elapsedRealtime() + 300000;
    synchronized (field(wm, "mGlobalLock")) {
      Object root = field(wm, "mRoot");
      Object dc = call(root, "getDisplayContent", 0);
      visit(dc, tasks, visible, Collections.newSetFromMap(new IdentityHashMap<>()));
      if (windows) {
        for (Object w : visible) {
          Object activity = field(w, "mActivityRecord");
          if (activity == null) continue;
          Object task = call(activity, "getTask");
          if (task == null) continue;
          ActivityInfo info = (ActivityInfo) field(activity, "info");
          if (info.applicationInfo.uid / 100000 != owner / 100000) continue;
          String title =
              ((android.view.WindowManager.LayoutParams) field(w, "mAttrs")).getTitle().toString();
          result.add(
              new Candidate(
                  UUID.randomUUID().toString(),
                  info.applicationInfo.uid,
                  XposedHelpers.getIntField(task, "mTaskId"),
                  info.packageName,
                  info.name,
                  title,
                  w,
                  activity,
                  expires));
        }
      } else {
        Set<Integer> ids = new HashSet<>();
        for (Object task : tasks) {
          Object activity = call(task, "getTopMostActivity");
          if (activity == null) continue;
          Object w = call(activity, "findMainWindow", false);
          if (!allowedWindow(w)) continue;
          int id = XposedHelpers.getIntField(task, "mTaskId");
          if (!ids.add(id)) continue;
          ActivityInfo info = (ActivityInfo) field(activity, "info");
          if (info.applicationInfo.uid / 100000 != owner / 100000) continue;
          result.add(
              new Candidate(
                  UUID.randomUUID().toString(),
                  info.applicationInfo.uid,
                  id,
                  info.packageName,
                  info.name,
                  "",
                  null,
                  activity,
                  expires));
        }
      }
    }
    PackageManager pm = rt.context.getPackageManager();
    Set<String> running = new HashSet<>();
    for (Candidate c : result) running.add(c.pkg);
    if (!windows) {
      Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      for (ResolveInfo r : pm.queryIntentActivities(launch, 0)) {
        ActivityInfo info = r.activityInfo;
        if (info == null || !info.exported || !running.add(info.packageName)) continue;
        result.add(
            new Candidate(
                UUID.randomUUID().toString(),
                info.applicationInfo.uid,
                -1,
                info.packageName,
                info.name,
                "",
                null,
                null,
                expires));
      }
    }
    ArrayList<Bundle> entries = new ArrayList<>();
    Map<String, Candidate> cache = new HashMap<>();
    for (Candidate c : result) {
      try {
        ApplicationInfo info = pm.getApplicationInfo(c.pkg, 0);
        Bundle entry = new Bundle();
        String app = info.loadLabel(pm).toString();
        String detail = windows ? c.detail : "";
        if (detail.contains("/")) detail = detail.substring(detail.lastIndexOf('/') + 1);
        entry.putString("id", c.id);
        entry.putString("label", app);
        entry.putString("detail", detail);
        entry.putString("package", c.pkg);
        entry.putInt("uid", c.uid);
        entries.add(entry);
        cache.put(c.id, c);
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    }
    entries.sort(
        Comparator.comparing(b -> b.getString("label", ""), java.text.Collator.getInstance()));
    catalogs.put(owner, cache);
    rt.worker.postDelayed(
        () -> {
          if (catalogs.get(owner) == cache) catalogs.remove(owner);
        },
        300000);
    Bundle out = new Bundle();
    out.putParcelableArrayList("entries", entries);
    return out;
  }

  private static Bundle activate(HookRuntime rt, int owner, Bundle data) {
    if (data == null) throw new IllegalArgumentException();
    Candidate c = catalogs.getOrDefault(owner, Map.of()).get(data.getString("id", ""));
    if (c == null || c.expires < SystemClock.elapsedRealtime())
      throw new IllegalStateException("Expired target");
    boolean strict = data.getBoolean("windows", false);
    if (strict && (c.window == null || !rt.options.singleWindow()))
      throw new IllegalStateException("Window selection disabled");
    Class<?> cookieClass =
        XposedHelpers.findClass("android.app.ActivityOptions$LaunchCookie", null);
    Object cookie = XposedHelpers.newInstance(cookieClass, "galaxy_capture_target");
    IBinder binder = (IBinder) field(cookie, "binder");
    if (c.task >= 0) {
      Object manager =
          XposedHelpers.callStaticMethod(
              XposedHelpers.findClass("android.app.ActivityTaskManager", null), "getService");
      call(manager, "startActivityFromRecents", c.task, null);
    } else {
      Intent launch = rt.context.getPackageManager().getLaunchIntentForPackage(c.pkg);
      if (launch == null) throw new IllegalStateException("App has no launcher");
      Object options = android.app.ActivityOptions.makeBasic();
      call(options, "setLaunchCookie", cookie);
      rt.context.startActivity(
          launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), (Bundle) call(options, "toBundle"));
    }
    selected =
        new Selection(
            owner, c.task, c.window, c.activity, strict, SystemClock.elapsedRealtime() + 60000);
    Bundle out = new Bundle();
    out.putBinder("cookie", binder);
    out.putInt("task_id", c.task);
    out.putInt("uid", c.uid);
    return out;
  }

  static Selection take(int owner, int task) {
    Selection choice = selected;
    if (choice != null
        && choice.owner == owner
        && choice.expires >= SystemClock.elapsedRealtime()
        && (choice.task < 0 || choice.task == task)) {
      selected = null;
      return choice;
    }
    return null;
  }

  private static Bundle audioUid(int owner) {
    Object wm = wm();
    int uid = -1;
    boolean app = false;
    synchronized (field(wm, "mGlobalLock")) {
      Object session =
          call(field(wm, "mContentRecordingController"), "getContentRecordingSessionLocked");
      if (session != null && (int) call(session, "getContentToRecord") == 1) {
        Object dc =
            call(
                field(wm, "mRoot"),
                "getDisplayContent",
                (int) call(session, "getVirtualDisplayId"));
        if (dc != null
            && XposedHelpers.getIntField(field(dc, "mDisplayInfo"), "ownerUid") == owner) {
          app = true;
          uid = (int) call(session, "getTargetUid");
        }
      }
    }
    Bundle out = new Bundle();
    out.putInt("uid", uid);
    out.putBoolean("app", app);
    return out;
  }
}
