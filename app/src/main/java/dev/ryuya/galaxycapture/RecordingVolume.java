package dev.ryuya.galaxycapture;

import dev.ryuya.galaxycapture.hook.*;

/** Only Samsung recorder PCM is affected. Device output and input routing stay native. */
final class RecordingVolume {
  private static final String LEVELS = "galaxycapture.audioLevels";
  private static final ThreadLocal<Object> mixedOwner = new ThreadLocal<>();
  static void install(HookRuntime rt, ClassLoader loader) {
    String base = "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.";
    for (String name : new String[] {"SystemAudioRecorder", "MicAudioRecorder", "EnhancedMicAudioRecorder"}) {
      Class<?> type = Reflect.findClass(base + name, loader);
      Hooks.hookAllMethods(type, "createAudioRecord", new HookCallback() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
          Reflect.setAdditionalInstanceField(p.thisObject, LEVELS, AudioLevels.from(rt.options));
        }
      });
    }
    Class<?> enhanced = Reflect.findClass(base + "EnhancedMicAudioRecorder", loader);
    Hooks.hookAllMethods(enhanced, "mixShortArrToByteBuffer", new HookCallback() {
      @Override protected void beforeHookedMethod(MethodHookParam p) {
        AudioLevels levels = levels(rt, p.thisObject);
        if (levels == null) return;
        try {
          Object buffer = p.args[0];
          short[][] data = (short[][]) Reflect.getObjectField(buffer, "shortDataArr");
          int[] valid = (int[]) Reflect.getObjectField(buffer, "readSize");
          AudioLevels.scale(data[0], valid[0], levels.mic());
          AudioLevels.scale(data[1], valid[1], levels.media());
          p.setObjectExtra("previousVolumeOwner", mixedOwner.get());
          p.setObjectExtra("volumeEntered", true);
          mixedOwner.set(Reflect.getObjectField(p.thisObject, "pRecorder"));
        } catch (Throwable e) { rt.fail(e); }
      }
      @Override protected void afterHookedMethod(MethodHookParam p) {
        if (!Boolean.TRUE.equals(p.getObjectExtra("volumeEntered"))) return;
        Object previous = p.getObjectExtra("previousVolumeOwner");
        if (previous == null) mixedOwner.remove(); else mixedOwner.set(previous);
      }
    });
    // Samsung otherwise applies the device playback-volume table to the digital stream.
    // Replace it only inside this recorder's mix callback when independent levels are enabled.
    Hooks.hookAllMethods(Reflect.findClass(base + "MP4Recorder", loader), "getAudioScale", new HookCallback() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        if (p.thisObject == mixedOwner.get() && rt.guard.allowed()) p.setResult(1f);
      }
    });
    Hooks.hookAllMethods(Reflect.findClass(base + "BaseAudioRecorder", loader), "putShortArrToByteBuffer", new HookCallback() {
      @Override protected void beforeHookedMethod(MethodHookParam p) {
        AudioLevels levels = levels(rt, p.thisObject);
        if (levels == null) return;
        String name = p.thisObject.getClass().getSimpleName();
        if (!name.equals("SystemAudioRecorder") && !name.equals("MicAudioRecorder")) return;
        try {
          short[][] data = (short[][]) Reflect.getObjectField(p.args[0], "shortDataArr");
          int[] valid = (int[]) Reflect.getObjectField(p.args[0], "readSize");
          AudioLevels.scale(data[0], valid[0], name.equals("SystemAudioRecorder") ? levels.media() : levels.mic());
        } catch (Throwable e) { rt.fail(e); }
      }
    });
    SamsungSettingsUi.install(rt, loader);
  }
  private static AudioLevels levels(HookRuntime rt, Object recorder) {
    AudioLevels value = (AudioLevels) Reflect.getAdditionalInstanceField(recorder, LEVELS);
    return value != null && value.enabled() && rt.guard.allowed() ? value : null;
  }
}
