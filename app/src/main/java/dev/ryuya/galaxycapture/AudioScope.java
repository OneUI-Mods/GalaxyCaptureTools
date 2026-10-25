package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import dev.ryuya.galaxycapture.hook.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

final class AudioScope {
  private static final String SPEAKER_MEDIA = "galaxycapture.speakerMedia";
  private static final String AUDIO_SELECTION = "galaxycapture.audioSelection";

  private record SamsungAudio(CallAudioPolicy.Selection selection, MediaProjection projection) {}

  private static final ThreadLocal<SamsungAudio> samsung = new ThreadLocal<>();
  private static final ThreadLocal<CallAudioPolicy.Selection> aosp = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> aospScope = new ThreadLocal<>();

  static void installSamsung(HookRuntime rt, ClassLoader loader) {
    SamsungAudioUi.install(rt, loader);
    for (String name : new String[] {"SystemAudioRecorder", "EnhancedMicAudioRecorder"}) {
      Class<?> cls =
          Reflect.findClass(
              "com.samsung.android.app.smartcapture.screenrecorder.recorder.core." + name, loader);
      Hooks.hookAllMethods(
          cls,
          "createAudioRecord",
          new HookCallback() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
              p.setObjectExtra("previousSamsungAudio", samsung.get());
              p.setObjectExtra("samsungAudioEntered", true);
              samsung.remove();
              Reflect.setAdditionalInstanceField(
                  p.thisObject, SPEAKER_MEDIA, rt.options.enabled() && rt.options.speakerMedia());
              if (!rt.options.enabled()) return;
              try {
                CallAudioPolicy.Selection selection =
                    (CallAudioPolicy.Selection)
                        Reflect.getAdditionalInstanceField(p.thisObject, AUDIO_SELECTION);
                if (selection == null) {
                  SamsungSelection.Target target = SamsungSelection.target();
                  selection =
                      new CallAudioPolicy.Selection(
                          target != null && target.audioOnly() ? target.uid() : -1,
                          rt.options.includeCallAudio());
                  Reflect.setAdditionalInstanceField(p.thisObject, AUDIO_SELECTION, selection);
                }
                Object owner = Reflect.getObjectField(p.thisObject, "pRecorder");
                samsung.set(new SamsungAudio(selection, SamsungSelection.audioProjection(owner)));
              } catch (Throwable error) {
                p.setThrowable(new IllegalStateException("Playback audio unavailable", error));
                rt.fail(error);
              }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
              if (!Boolean.TRUE.equals(p.getObjectExtra("samsungAudioEntered"))) return;
              SamsungAudio previous = (SamsungAudio) p.getObjectExtra("previousSamsungAudio");
              if (previous == null) samsung.remove();
              else samsung.set(previous);
            }
          });
    }
    int playback =
        Reflect.getStaticIntField(
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.recorder."
                    + "common.ScreenRecorderDefine$AudioSource",
                loader),
            "SEM_PLAYBACK_RECORD");
    Reflect.findAndHookMethod(
        AudioRecord.Builder.class,
        "setAudioSource",
        int.class,
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            SamsungAudio scope = samsung.get();
            if (scope == null || (int) p.args[0] != playback) return;
            try {
              AudioPlaybackCaptureConfiguration.Builder capture =
                  new AudioPlaybackCaptureConfiguration.Builder(scope.projection())
                      .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                      .addMatchingUsage(AudioAttributes.USAGE_GAME)
                      .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
              CallAudioCapture.configure(capture, scope.selection());
              ((AudioRecord.Builder) p.thisObject).setAudioPlaybackCaptureConfig(capture.build());
              p.setResult(p.thisObject);
              rt.report(
                  scope.selection().includeCalls()
                      ? "Recording playback including available call audio"
                      : "Recording media playback without call audio");
            } catch (Throwable error) {
              p.setThrowable(
                  new IllegalStateException("Playback audio filtering unavailable", error));
              rt.fail(error);
            }
          }
        });
    // Enter Samsung's existing digital-mix branch only inside its audio-buffer callback.
    // Never change routing, preferred input, or the actual headphone connection state.
    Class<?> ownerClass =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder",
            loader);
    Reflect.findAndHookMethod(
        ownerClass,
        "isPlugging",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (SpeakerMixContext.includePlayback(p.thisObject)) p.setResult(true);
          }
        });
    Reflect.findAndHookMethod(
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.EnhancedMicAudioRecorder",
            loader),
        "mixShortArrToByteBuffer",
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.AudioBuffer",
            loader),
        ByteBuffer.class,
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!Boolean.TRUE.equals(
                Reflect.getAdditionalInstanceField(p.thisObject, SPEAKER_MEDIA))) return;
            try {
              Object owner = Reflect.getObjectField(p.thisObject, "pRecorder");
              p.setObjectExtra("previousMixer", SpeakerMixContext.enter(owner));
              p.setObjectExtra("speakerMedia", true);
            } catch (Throwable error) {
              rt.fail(error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(p.getObjectExtra("speakerMedia")))
              SpeakerMixContext.restore(p.getObjectExtra("previousMixer"));
          }
        });
  }

  static void installAosp(HookRuntime rt, ClassLoader loader) {
    Hooks.hookAllMethods(
        Reflect.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader),
        "start",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            if (!rt.options.enabled()) return;
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
    Class<?> recorder =
        Reflect.findClass("com.android.systemui.screenrecord.ScreenInternalAudioRecorder", loader);
    Hooks.hookAllConstructors(
        recorder,
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previousAospAudio", aosp.get());
            p.setObjectExtra("aospAudioEntered", true);
            aosp.remove();
            if (!rt.options.enabled()) return;
            try {
              Boolean scope = aospScope.get();
              boolean appOnly = scope == null ? rt.options.appAudioOnly() : scope;
              int uid = appOnly ? resolve(rt) : -1;
              aosp.set(new CallAudioPolicy.Selection(uid, rt.options.includeCallAudio()));
            } catch (Throwable error) {
              p.setThrowable(new java.io.IOException("App audio filtering unavailable", error));
              rt.fail(error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!Boolean.TRUE.equals(p.getObjectExtra("aospAudioEntered"))) return;
            CallAudioPolicy.Selection previous =
                (CallAudioPolicy.Selection) p.getObjectExtra("previousAospAudio");
            if (previous == null) aosp.remove();
            else aosp.set(previous);
          }
        });
    Hooks.hookAllMethods(
        AudioPlaybackCaptureConfiguration.Builder.class,
        "build",
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            CallAudioPolicy.Selection selection = aosp.get();
            if (selection != null)
              CallAudioCapture.configure(
                  (AudioPlaybackCaptureConfiguration.Builder) p.thisObject, selection);
          }
        });
  }

  private static int resolve(HookRuntime rt) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    int[] uid = {-2};
    TargetBridge.request(
        rt,
        "audio_uid",
        null,
        new ResultReceiver(rt.worker) {
          @Override
          protected void onReceiveResult(int code, Bundle result) {
            if (code == Activity.RESULT_OK && result != null) {
              int value = result.getInt("uid", -1);
              if (value >= 0 || !result.getBoolean("app", false)) uid[0] = value;
            }
            latch.countDown();
          }
        });
    if (!latch.await(1500, TimeUnit.MILLISECONDS) || uid[0] == -2)
      throw new IllegalStateException("Audio target not resolved");
    return uid[0];
  }
}
