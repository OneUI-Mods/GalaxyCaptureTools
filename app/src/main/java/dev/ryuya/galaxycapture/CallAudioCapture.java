package dev.ryuya.galaxycapture;

import android.content.AttributionSource;
import android.media.AudioAttributes;
import android.media.AudioPlaybackCaptureConfiguration;
import android.os.Binder;
import dev.ryuya.galaxycapture.hook.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Adds voice playback to a consented screen recording without granting a package permission. */
final class CallAudioCapture {
  static void configure(
      AudioPlaybackCaptureConfiguration.Builder builder, CallAudioPolicy.Selection selection) {
    for (CallAudioPolicy.Criterion criterion : selection.additionalRules()) {
      if (criterion.rule() == 4) {
        builder.addMatchingUid(criterion.uid());
      } else {
        AudioAttributes.Builder attributes =
            new AudioAttributes.Builder().setUsage(criterion.usage());
        Reflect.callMethod(attributes, "addTag", CallAudioPolicy.TAG);
        Reflect.callMethod(
            Reflect.getObjectField(builder, "mAudioMixingRuleBuilder"),
            "addRule",
            attributes.build(),
            criterion.rule());
      }
    }
  }

  static void installFramework(ClassLoader loader) {
    Class<?> service = Reflect.findClass("com.android.server.audio.AudioService", loader);
    Method registration;
    try {
      registration =
          service.getDeclaredMethod(
              "registerAudioPolicy",
              Reflect.findClass("android.media.audiopolicy.AudioPolicyConfig", loader),
              Reflect.findClass("android.media.audiopolicy.IAudioPolicyCallback", loader),
              boolean.class,
              boolean.class,
              boolean.class,
              boolean.class,
              Reflect.findClass("android.media.projection.IMediaProjection", loader),
              AttributionSource.class);
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("Unreviewed audio policy registration contract", error);
    }
    Reflect.findAndHookMethod(
        service,
        "callerHasPermission",
        String.class,
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (!p.hasThrowable()
                && CallAudioPermissionScope.allows(
                    p.thisObject, Binder.getCallingUid(), (String) p.args[0])) p.setResult(true);
          }
        });
    // callerHasPermission is small and can otherwise be inlined into this caller.
    Hooks.deoptimize(registration);
    Reflect.findAndHookMethod(
        service,
        "registerAudioPolicy",
        Reflect.findClass("android.media.audiopolicy.AudioPolicyConfig", loader),
        Reflect.findClass("android.media.audiopolicy.IAudioPolicyCallback", loader),
        boolean.class,
        boolean.class,
        boolean.class,
        boolean.class,
        Reflect.findClass("android.media.projection.IMediaProjection", loader),
        AttributionSource.class,
        new HookCallback() {
          @Override
          protected void beforeHookedMethod(MethodHookParam p) {
            p.setObjectExtra("previousCallPermission", CallAudioPermissionScope.enter(null));
            p.setObjectExtra("callPermissionEntered", true);
            HookRuntime rt = FrameworkHooks.runtime;
            if (rt == null || !rt.options.enabled() || !rt.options.includeCallAudio()) return;
            try {
              if (authorized(rt, p.args, loader))
                CallAudioPermissionScope.enter(
                    new CallAudioPermissionScope.Grant(p.thisObject, Binder.getCallingUid()));
            } catch (Throwable error) {
              // An unknown contract leaves the original permission check in charge.
              rt.report("Call audio unavailable; native audio permission checks retained");
              Hooks.log("GalaxyCapture call audio contract: " + error);
            }
          }

          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(p.getObjectExtra("callPermissionEntered")))
              CallAudioPermissionScope.restore(
                  (CallAudioPermissionScope.Grant) p.getObjectExtra("previousCallPermission"));
          }
        });
  }

  private static boolean authorized(HookRuntime rt, Object[] args, ClassLoader loader) {
    if (args[6] == null || !(args[7] instanceof AttributionSource attribution)) return false;
    int uid = Binder.getCallingUid();
    String[] packages = rt.context.getPackageManager().getPackagesForUid(uid);
    CallAudioPolicy.Caller caller =
        new CallAudioPolicy.Caller(
            uid,
            attribution.getUid(),
            attribution.getPackageName(),
            packages != null && Arrays.asList(packages).contains(attribution.getPackageName()));
    boolean changesFocus =
        (boolean) args[2] || (boolean) args[3] || (boolean) args[4] || (boolean) args[5];
    if (!CallAudioPolicy.trustedCaller(caller) || changesFocus) return false;
    List<?> nativeMixes = (List<?>) Reflect.callMethod(args[0], "getMixes");
    if (nativeMixes.size() != 1) return false;
    List<CallAudioPolicy.Mix> mixes = new ArrayList<>();
    for (Object mix : nativeMixes) {
      Object rule = Reflect.callMethod(mix, "getRule");
      List<CallAudioPolicy.Criterion> criteria = new ArrayList<>();
      for (Object criterion : (List<?>) Reflect.callMethod(rule, "getCriteria")) {
        int kind = (int) Reflect.callMethod(criterion, "getRule");
        AudioAttributes attributes =
            (AudioAttributes) Reflect.callMethod(criterion, "getAudioAttributes");
        boolean tagged =
            attributes != null
                && ((Collection<?>) Reflect.callMethod(attributes, "getTags"))
                    .contains(CallAudioPolicy.TAG);
        criteria.add(
            new CallAudioPolicy.Criterion(
                kind,
                attributes == null ? -1 : attributes.getUsage(),
                (int) Reflect.callMethod(criterion, "getIntProp"),
                tagged));
      }
      mixes.add(
          new CallAudioPolicy.Mix(
              (int) Reflect.callMethod(mix, "getRouteFlags"),
              (int) Reflect.callMethod(rule, "getTargetMixRole"),
              (boolean) Reflect.callMethod(rule, "allowPrivilegedMediaPlaybackCapture"),
              (boolean) Reflect.callMethod(mix, "isForCallRedirection"),
              criteria));
    }
    // Reject unrelated policies before touching the projection service.
    if (!CallAudioPolicy.authorize(true, true, caller, true, changesFocus, mixes)) return false;
    Object binder =
        Reflect.callStaticMethod(
            Reflect.findClass("android.os.ServiceManager", loader),
            "getService",
            "media_projection");
    Object manager =
        Reflect.callStaticMethod(
            Reflect.findClass("android.media.projection.IMediaProjectionManager$Stub", loader),
            "asInterface",
            binder);
    return (boolean) Reflect.callMethod(manager, "isCurrentProjection", args[6])
        && (boolean) Reflect.callMethod(args[6], "canProjectAudio");
  }
}
