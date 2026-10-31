package dev.ryuya.galaxycapture

import android.content.AttributionSource
import android.media.AudioAttributes
import android.os.Binder
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Arrays
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Adds voice playback to a consented screen recording without granting a package permission. */
object CallAudioCapture {
    @JvmStatic
    fun configure(
        builder: android.media.AudioPlaybackCaptureConfiguration.Builder,
        selection: CallAudioPolicy.Selection,
    ) {
        for (criterion in selection.additionalRules()!!) {
            if (criterion!!.rule == 4) {
                builder.addMatchingUid(criterion!!.uid)
            } else {
                val attributes = android.media.AudioAttributes.Builder().setUsage(criterion!!.usage)
                Reflect.callMethod(attributes, "addTag", CallAudioPolicy.TAG)
                Reflect.callMethod(
                    Reflect.getObjectField(builder, "mAudioMixingRuleBuilder")!!,
                    "addRule",
                    attributes.build(),
                    criterion!!.rule,
                )
            }
        }
    }

    @JvmStatic
    fun installFramework(loader: ClassLoader?) {
        val service = Reflect.findClass("com.android.server.audio.AudioService", loader)
        val registration: Method?
        try {
            registration =
                service!!.getDeclaredMethod(
                    "registerAudioPolicy",
                    Reflect.findClass("android.media.audiopolicy.AudioPolicyConfig", loader),
                    Reflect.findClass("android.media.audiopolicy.IAudioPolicyCallback", loader),
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Reflect.findClass("android.media.projection.IMediaProjection", loader),
                    AttributionSource::class.java,
                )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Unreviewed audio policy registration contract", error)
        }
        Reflect.findAndHookMethod(
            service,
            "callerHasPermission",
            String::class.java,
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (
                        !p.hasThrowable() &&
                            CallAudioPermissionScope.allows(
                                p.thisObject,
                                Binder.getCallingUid(),
                                p!!.args!![0] as String?,
                            )
                    )
                        p.setResult(true)
                }
            },
        )
        // callerHasPermission is small and can otherwise be inlined into this caller.
        Hooks.deoptimize(registration)
        Reflect.findAndHookMethod(
            service,
            "registerAudioPolicy",
            Reflect.findClass("android.media.audiopolicy.AudioPolicyConfig", loader),
            Reflect.findClass("android.media.audiopolicy.IAudioPolicyCallback", loader),
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Reflect.findClass("android.media.projection.IMediaProjection", loader),
            AttributionSource::class.java,
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("previousCallPermission", CallAudioPermissionScope.enter(null))
                    p.setObjectExtra("callPermissionEntered", true)
                    val rt = FrameworkHooks.runtime
                    if (rt == null || !rt.options!!.enabled || !rt.options!!.includeCallAudio)
                        return
                    try {
                        if (CallAudioCapture.authorized(rt, p.args!!, loader))
                            CallAudioPermissionScope.enter(
                                CallAudioPermissionScope.Grant(p.thisObject, Binder.getCallingUid())
                            )
                    } catch (error: Throwable) {
                        // An unknown contract leaves the original permission check in charge.
                        rt.report("Call audio unavailable; native audio permission checks retained")
                        Hooks.log("GalaxyCapture call audio contract: " + error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE == p.getObjectExtra("callPermissionEntered"))
                        CallAudioPermissionScope.restore(
                            p.getObjectExtra("previousCallPermission")
                                as CallAudioPermissionScope.Grant?
                        )
                }
            },
        )
    }

    @JvmStatic
    private fun authorized(rt: HookRuntime, args: Array<Any?>, loader: ClassLoader?): Boolean {
        if (args[6] == null || args[7] !is AttributionSource) return false
        val attribution = args[7] as AttributionSource
        val uid = Binder.getCallingUid()
        val packages = rt.context.getPackageManager().getPackagesForUid(uid)
        val caller =
            CallAudioPolicy.Caller(
                uid,
                attribution.getUid(),
                attribution.getPackageName(),
                packages != null &&
                    Arrays.asList<String?>(*packages).contains(attribution.getPackageName()),
            )
        val changesFocus: Boolean =
            args[2] as Boolean || args[3] as Boolean || args[4] as Boolean || args[5] as Boolean
        if (!CallAudioPolicy.trustedCaller(caller) || changesFocus) return false
        val nativeMixes: MutableList<*>? =
            Reflect.callMethod(args[0]!!, "getMixes") as MutableList<*>?
        if (nativeMixes!!.size != 1) return false
        val mixes = ArrayList<CallAudioPolicy.Mix?>()
        for (mix in nativeMixes!!) {
            val rule = Reflect.callMethod(mix!!, "getRule")
            val criteria = ArrayList<CallAudioPolicy.Criterion?>()
            for (criterion in Reflect.callMethod(rule!!, "getCriteria") as MutableList<*>) {
                val kind: Int = Reflect.callMethod(criterion!!, "getRule") as Int
                val attributes: AudioAttributes? =
                    Reflect.callMethod(criterion!!, "getAudioAttributes") as AudioAttributes?
                val tagged: Boolean =
                    attributes != null &&
                        (Reflect.callMethod(attributes, "getTags") as MutableCollection<*>)
                            .contains(CallAudioPolicy.TAG)
                criteria.add(
                    CallAudioPolicy.Criterion(
                        kind,
                        if (attributes == null) -1 else attributes.getUsage(),
                        Reflect.callMethod(criterion!!, "getIntProp") as Int,
                        tagged,
                    )
                )
            }
            mixes.add(
                CallAudioPolicy.Mix(
                    Reflect.callMethod(mix!!, "getRouteFlags") as Int,
                    Reflect.callMethod(rule!!, "getTargetMixRole") as Int,
                    Reflect.callMethod(rule!!, "allowPrivilegedMediaPlaybackCapture") as Boolean,
                    Reflect.callMethod(mix!!, "isForCallRedirection") as Boolean,
                    criteria,
                )
            )
        }
        // Reject unrelated policies before touching the projection service.
        if (!CallAudioPolicy.authorize(true, true, caller, true, changesFocus, mixes)) return false
        val binder =
            Reflect.callStaticMethod(
                Reflect.findClass("android.os.ServiceManager", loader),
                "getService",
                "media_projection",
            )
        val manager =
            Reflect.callStaticMethod(
                Reflect.findClass("android.media.projection.IMediaProjectionManager\$Stub", loader),
                "asInterface",
                binder,
            )
        return Reflect.callMethod(manager!!, "isCurrentProjection", args[6]) as Boolean &&
            Reflect.callMethod(args[6]!!, "canProjectAudio") as Boolean
    }
}
