package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import de.robv.android.xposed.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

final class AudioScope {
  private static final String SPEAKER_MEDIA = "galaxycapture.speakerMedia";
  private record SamsungAudio(int uid, MediaProjection projection) {}

  private static final ThreadLocal<SamsungAudio> samsung = new ThreadLocal<>();
  private static final ThreadLocal<Integer> aosp = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> aospScope = new ThreadLocal<>();

  static void installSamsung(HookRuntime rt, ClassLoader loader) {
    SamsungAudioUi.install(rt, loader);
    for (String name : new String[] {"SystemAudioRecorder", "EnhancedMicAudioRecorder"}) {
      Class<?> cls = XposedHelpers.findClass(
          "com.samsung.android.app.smartcapture.screenrecorder.recorder.core." + name, loader);
      XposedBridge.hookAllMethods(cls, "createAudioRecord", new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
          XposedHelpers.setAdditionalInstanceField(
              p.thisObject, SPEAKER_MEDIA, rt.options.enabled() && rt.options.speakerMedia());
          SamsungSelection.Target t = SamsungSelection.target();
          if (!rt.options.enabled() || t == null || !t.audioOnly())
            return;
          try {
            Object owner = XposedHelpers.getObjectField(p.thisObject, "pRecorder");
            samsung.set(
                new SamsungAudio(t.uid(), SamsungSelection.audioProjection(owner)));
          } catch (Throwable error) {
            p.setThrowable(new IllegalStateException("App audio unavailable", error));
            rt.fail(error);
          }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
          samsung.remove();
        }
      });
    }
    int playback = XposedHelpers.getStaticIntField(
        XposedHelpers.findClass("com.samsung.android.app.smartcapture.screenrecorder.recorder."
                                + "common.ScreenRecorderDefine$AudioSource",
            loader),
        "SEM_PLAYBACK_RECORD");
    XposedHelpers.findAndHookMethod(
        AudioRecord.Builder.class, "setAudioSource", int.class, new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            SamsungAudio scope = samsung.get();
            if (scope == null || (int) p.args[0] != playback)
              return;
            try {
              AudioPlaybackCaptureConfiguration capture =
                  new AudioPlaybackCaptureConfiguration.Builder(scope.projection())
                      .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                      .addMatchingUsage(AudioAttributes.USAGE_GAME)
                      .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                      .addMatchingUid(scope.uid())
                      .build();
              ((AudioRecord.Builder) p.thisObject).setAudioPlaybackCaptureConfig(capture);
              p.setResult(p.thisObject);
              rt.report("Recording target app audio only");
            } catch (Throwable error) {
              p.setThrowable(new IllegalStateException("App audio filtering unavailable", error));
              rt.fail(error);
            }
          }
        });
    // Enter Samsung's existing digital-mix branch only inside its audio-buffer callback.
    // Never change routing, preferred input, or the actual headphone connection state.
    Class<?> ownerClass = XposedHelpers.findClass(
        "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder", loader);
    XposedHelpers.findAndHookMethod(ownerClass, "isPlugging", new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        if (SpeakerMixContext.includePlayback(p.thisObject)) p.setResult(true);
      }
    });
    XposedHelpers.findAndHookMethod(
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.EnhancedMicAudioRecorder", loader),
        "mixShortArrToByteBuffer",
        XposedHelpers.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.AudioBuffer", loader),
        ByteBuffer.class, new XC_MethodHook() {
          @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject, SPEAKER_MEDIA)))
              return;
            try {
              Object owner = XposedHelpers.getObjectField(p.thisObject, "pRecorder");
              p.setObjectExtra("previousMixer", SpeakerMixContext.enter(owner));
              p.setObjectExtra("speakerMedia", true);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }
          @Override protected void afterHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(p.getObjectExtra("speakerMedia")))
              SpeakerMixContext.restore(p.getObjectExtra("previousMixer"));
          }
        });
  }

  static void installAosp(HookRuntime rt, ClassLoader loader) {
    XposedBridge.hookAllMethods(
        XposedHelpers.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader),
        "start", new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled())
              return;
            try {
              aospScope.set(AospRecordingUi.takeScope(p.thisObject, rt.options.appAudioOnly()));
            } catch (Throwable error) {
              p.setThrowable(new java.io.IOException("Audio scope unavailable", error));
              rt.fail(error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            aospScope.remove();
          }
        });
    Class<?> recorder = XposedHelpers.findClass(
        "com.android.systemui.screenrecord.ScreenInternalAudioRecorder", loader);
    XposedBridge.hookAllConstructors(recorder, new XC_MethodHook() {
      @Override
      protected void beforeHookedMethod(MethodHookParam p) {
        Boolean scope = aospScope.get();
        if (!rt.options.enabled() || !(scope == null ? rt.options.appAudioOnly() : scope))
          return;
        try {
          int uid = resolve(rt);
          if (uid >= 0)
            aosp.set(uid);
        } catch (Throwable error) {
          p.setThrowable(new java.io.IOException("App audio filtering unavailable", error));
          rt.fail(error);
        }
      }

      @Override
      protected void afterHookedMethod(MethodHookParam p) {
        aosp.remove();
      }
    });
    XposedBridge.hookAllMethods(
        AudioPlaybackCaptureConfiguration.Builder.class, "build", new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            Integer uid = aosp.get();
            if (uid != null)
              ((AudioPlaybackCaptureConfiguration.Builder) p.thisObject).addMatchingUid(uid);
          }
        });
  }

  private static int resolve(HookRuntime rt) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    int[] uid = {-2};
    TargetBridge.request(rt, "audio_uid", null, new ResultReceiver(rt.worker) {
      @Override
      protected void onReceiveResult(int code, Bundle result) {
        if (code == Activity.RESULT_OK && result != null) {
          int value = result.getInt("uid", -1);
          if (value >= 0 || !result.getBoolean("app", false))
            uid[0] = value;
        }
        latch.countDown();
      }
    });
    if (!latch.await(1500, TimeUnit.MILLISECONDS) || uid[0] == -2)
      throw new IllegalStateException("Audio target not resolved");
    return uid[0];
  }
}
