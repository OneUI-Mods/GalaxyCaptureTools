package dev.ryuya.galaxycapture;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;
import de.robv.android.xposed.*;
import java.util.*;

/** Only alters mirrors owned by the two supported screen recorders. */
final class FrameworkHooks {
  static volatile HookRuntime runtime;
  private static final Map<Object, Session> sessions = new WeakHashMap<>();
  private static final ThreadLocal<Object> display = new ThreadLocal<>();
  private static Class<?> containerClass;

  private static final class Session {
    final Object recordingSession, activity;
    final Options options;
    final int rotation;
    Object window;

    Session(
        Object recordingSession, Object activity, Object window, Options options, int rotation) {
      this.recordingSession = recordingSession;
      this.activity = activity;
      this.window = window;
      this.options = options;
      this.rotation = rotation;
    }
  }

  static Object field(Object object, String name) {
    return XposedHelpers.getObjectField(object, name);
  }

  static Object call(Object object, String name, Object... args) {
    return XposedHelpers.callMethod(object, name, args);
  }

  private static boolean supported(Object recorder) {
    Object info = field(field(recorder, "mDisplayContent"), "mDisplayInfo");
    Object owner = field(info, "ownerPackageName");
    return "com.android.systemui".equals(owner)
        || "com.samsung.android.app.smartcapture".equals(owner);
  }

  private static int rotation(Object recorder) {
    Object source = field(recorder, "mRecordedWindowContainer");
    return XposedHelpers.getIntField(
        field(field(source, "mDisplayContent"), "mDisplayInfo"), "rotation");
  }

  private static void stop(Object recorder) {
    try {
      call(recorder, "stopMediaProjection", 4);
    } catch (Throwable ignored) {
    }
  }

  private static void fail(Object recorder, Throwable error) {
    if (runtime != null) runtime.fail(error);
    stop(recorder);
  }

  static void install(ClassLoader loader) {
    containerClass = XposedHelpers.findClass("com.android.server.wm.WindowContainer", loader);
    Class<?> dc = XposedHelpers.findClass("com.android.server.wm.DisplayContent", loader),
        cr = XposedHelpers.findClass("com.android.server.wm.ContentRecorder", loader);
    XposedBridge.hookAllMethods(
        dc,
        "updateRecording",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previous", display.get());
            display.remove();
            HookRuntime rt = runtime;
            if (rt == null) return;
            Object recorder = field(p.thisObject, "mContentRecorder");
            if (recorder == null) return;
            Object nativeSession = field(recorder, "mContentRecordingSession");
            if (nativeSession == null || !supported(recorder)) return;
            Session old = sessions.get(recorder);
            if (old != null && old.recordingSession != nativeSession) {
              sessions.remove(recorder);
              old = null;
            }
            if (old == null && !rt.options.enabled()) return;
            if (!rt.guard.allowed()) {
              p.setResult(null);
              stop(recorder);
              return;
            }
            try {
              int type = (int) call(nativeSession, "getContentToRecord");
              if (type != 0 && type != 1)
                return; // Do not expand Samsung's partial-area/overlay recordings.
              Options options = old == null ? rt.options : old.options;
              if (!options.rotation() && !(options.singleWindow() && type == 1)) return;
              if (old == null) {
                Object task =
                    type == 1
                        ? XposedHelpers.callStaticMethod(
                            containerClass, "fromBinder", call(nativeSession, "getTokenToRecord"))
                        : null;
                Object activity =
                    options.singleWindow() && task != null
                        ? call(task, "getTopMostActivity")
                        : null;
                Object window = activity == null ? null : call(activity, "findMainWindow");
                if (options.singleWindow()
                    && type == 1
                    && (window == null || !validWindow(window))) {
                  p.setResult(null);
                  return;
                }
                // Record initial rotation from the chosen source display, before it can resize.
                Object source =
                    task == null
                        ? call(
                            field(field(field(recorder, "mDisplayContent"), "mWmService"), "mRoot"),
                            "getDisplayContent",
                            (int) call(nativeSession, "getDisplayToRecord"))
                        : task;
                Object sourceDisplay = task == null ? source : field(source, "mDisplayContent");
                int initial =
                    XposedHelpers.getIntField(field(sourceDisplay, "mDisplayInfo"), "rotation");
                rt.guard.arm();
                old = new Session(nativeSession, activity, window, options, initial);
                sessions.put(recorder, old);
              }
              if (old.activity != null) {
                Object current = call(old.activity, "findMainWindow");
                if (!(boolean) call(old.activity, "isAttached")) {
                  p.setResult(null);
                  stop(recorder);
                  return;
                }
                if (current != null && validWindow(current) && current != old.window) {
                  old.window = current;
                  replaceWindowMirror(recorder, old);
                }
                if (!validWindow(old.window)) {
                  Object surface = field(recorder, "mRecordedSurface");
                  if (surface != null) {
                    Object tx = call(old.activity, "getSyncTransaction");
                    call(tx, "setAlpha", surface, 0f);
                    call(old.activity, "scheduleAnimation");
                  }
                  p.setResult(null);
                  return;
                }
              }
              display.set(p.thisObject);
              p.setObjectExtra("recorder", recorder);
            } catch (Throwable error) {
              p.setResult(null);
              fail(recorder, error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Object recorder = p.getObjectExtra("recorder");
            try {
              if (recorder != null && p.hasThrowable()) {
                Throwable error = p.getThrowable();
                p.setResult(null);
                fail(recorder, error);
              }
            } finally {
              Object previous = p.getObjectExtra("previous");
              if (previous == null) display.remove();
              else display.set(previous);
            }
          }
        });
    XposedBridge.hookAllMethods(
        SurfaceControl.class,
        "mirrorSurface",
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Object dc = display.get();
            if (dc == null || p.args.length == 0) return;
            Object recorder = field(dc, "mContentRecorder");
            Session s = sessions.get(recorder);
            if (s == null || s.activity == null) return;
            try {
              Object source = field(recorder, "mRecordedWindowContainer");
              if (source == null || p.args[0] != call(source, "getSurfaceControl")) return;
              if (!validWindow(s.window))
                throw new IllegalStateException("Selected window unavailable");
              p.args[0] = call(s.window, "getSurfaceControl");
            } catch (Throwable error) {
              p.setThrowable(
                  new IllegalStateException("Selected window cannot be mirrored", error));
            }
          }
        });
    XposedBridge.hookAllMethods(
        cr,
        "updateMirroredSurface",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            Session s = sessions.get(p.thisObject);
            if (s == null
                && runtime != null
                && runtime.options.enabled()
                && runtime.options.rotation()
                && supported(p.thisObject)) {
              try {
                Object nativeSession = field(p.thisObject, "mContentRecordingSession");
                if (nativeSession == null || (int) call(nativeSession, "getContentToRecord") != 0)
                  return;
                runtime.guard.arm();
                s = new Session(nativeSession, null, null, runtime.options, rotation(p.thisObject));
                sessions.put(p.thisObject, s);
              } catch (Throwable error) {
                fail(p.thisObject, error);
                return;
              }
            }
            if (s == null) return;
            try {
              if (p.hasThrowable()) throw p.getThrowable();
              Object surface = field(p.thisObject, "mRecordedSurface");
              if (surface == null) return;
              Rect source = (Rect) p.args[1];
              if (s.activity != null) {
                if (!validWindow(s.window))
                  throw new IllegalStateException("Selected window removed");
                source = (Rect) field(field(s.window, "mWindowFrames"), "mFrame");
              }
              Point output = (Point) p.args[2];
              int q =
                  s.options.rotation() ? Math.floorMod(rotation(p.thisObject) - s.rotation, 4) : 0;
              var t =
                  RecordingTransform.fit(source.width(), source.height(), output.x, output.y, q);
              call(p.args[0], "setWindowCrop", surface, source.width(), source.height());
              call(p.args[0], "setMatrix", surface, t.a(), t.b(), t.c(), t.d());
              call(p.args[0], "setPosition", surface, t.x(), t.y());
              if (s.activity != null) call(p.args[0], "setAlpha", surface, 1f);
              runtime.report(
                  "Recording; rotation="
                      + (q * 90)
                      + "; window="
                      + (s.activity != null)
                      + "; output="
                      + output.x
                      + "x"
                      + output.y);
            } catch (Throwable error) {
              fail(p.thisObject, error);
            }
          }
        });
    XposedBridge.hookAllMethods(
        cr,
        "clearContentRecordingSession",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            sessions.remove(p.thisObject);
            if (sessions.isEmpty() && runtime != null) {
              runtime.guard.finish();
              runtime.report("Ready; recording ended");
            }
          }
        });
  }

  private static void replaceWindowMirror(Object recorder, Session session) {
    Object previous = field(recorder, "mRecordedSurface");
    if (previous == null) return;
    SurfaceControl next =
        (SurfaceControl)
            XposedHelpers.callStaticMethod(
                SurfaceControl.class, "mirrorSurface", call(session.window, "getSurfaceControl"));
    Object dc = field(recorder, "mDisplayContent");
    Object tx = call(session.activity, "getSyncTransaction");
    call(tx, "reparent", next, call(dc, "getSurfaceControl"));
    call(tx, "remove", previous);
    XposedHelpers.setObjectField(recorder, "mRecordedSurface", next);
    Object size = call(recorder, "fetchSurfaceSizeIfPresent");
    call(recorder, "updateMirroredSurface", tx, call(session.activity, "getBounds"), size);
    call(session.activity, "scheduleAnimation");
  }

  private static boolean validWindow(Object window) {
    if (window == null || !(boolean) call(window, "isAttached")) return false;
    Object attrs = field(window, "mAttrs");
    if ((XposedHelpers.getIntField(attrs, "flags") & 8192) != 0) return false;
    SurfaceControl surface = (SurfaceControl) call(window, "getSurfaceControl");
    return surface != null && surface.isValid();
  }
}
