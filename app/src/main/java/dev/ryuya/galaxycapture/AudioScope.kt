package dev.ryuya.galaxycapture

import android.app.Activity
import android.media.AudioAttributes
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.ResultReceiver
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.io.IOException
import java.lang.Boolean as JavaBoolean
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

object AudioScope {
    private const val SPEAKER_MEDIA: String = "galaxycapture.speakerMedia"
    private const val AUDIO_SELECTION: String = "galaxycapture.audioSelection"

    private val samsung: ThreadLocal<AudioScope.SamsungAudio?> =
        ThreadLocal<AudioScope.SamsungAudio?>()
    private val aosp: ThreadLocal<CallAudioPolicy.Selection?> =
        ThreadLocal<CallAudioPolicy.Selection?>()
    private val aospScope: ThreadLocal<Boolean?> = ThreadLocal<Boolean?>()

    @JvmStatic
    fun installSamsung(rt: HookRuntime, loader: ClassLoader?) {
        SamsungAudioUi.install(rt, loader)
        for (name in arrayOf<String>("SystemAudioRecorder", "EnhancedMicAudioRecorder")) {
            val cls =
                Reflect.findClass(
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder.core." + name,
                    loader,
                )
            Hooks.hookAllMethods(
                cls,
                "createAudioRecord",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        p.setObjectExtra("previousSamsungAudio", AudioScope.samsung.get())
                        p.setObjectExtra("samsungAudioEntered", true)
                        AudioScope.samsung.remove()
                        Reflect.setAdditionalInstanceField(
                            p.thisObject,
                            AudioScope.SPEAKER_MEDIA,
                            rt.options!!.enabled && rt.options!!.speakerMedia,
                        )
                        if (!rt.options!!.enabled) return
                        try {
                            var selection: CallAudioPolicy.Selection? =
                                Reflect.getAdditionalInstanceField(
                                    p.thisObject,
                                    AudioScope.AUDIO_SELECTION,
                                ) as CallAudioPolicy.Selection?
                            if (selection == null) {
                                val target = SamsungSelection.target()
                                selection =
                                    CallAudioPolicy.Selection(
                                        if (target != null && target.audioOnly) target.uid else -1,
                                        rt.options!!.includeCallAudio,
                                    )
                                Reflect.setAdditionalInstanceField(
                                    p.thisObject,
                                    AudioScope.AUDIO_SELECTION,
                                    selection,
                                )
                            }
                            val owner = Reflect.getObjectField(p.thisObject!!, "pRecorder")
                            AudioScope.samsung.set(
                                AudioScope.SamsungAudio(
                                    selection,
                                    SamsungSelection.audioProjection(owner),
                                )
                            )
                        } catch (error: Throwable) {
                            p.setThrowable(
                                IllegalStateException("Playback audio unavailable", error)
                            )
                            rt.fail(error)
                        }
                    }

                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (JavaBoolean.TRUE != p.getObjectExtra("samsungAudioEntered")) return
                        val previous: AudioScope.SamsungAudio? =
                            p.getObjectExtra("previousSamsungAudio") as AudioScope.SamsungAudio?
                        if (previous == null) AudioScope.samsung.remove()
                        else AudioScope.samsung.set(previous)
                    }
                },
            )
        }
        val playback =
            Reflect.getStaticIntField(
                Reflect.findClass(
                    "com.samsung.android.app.smartcapture.screenrecorder.recorder." +
                        "common.ScreenRecorderDefine\$AudioSource",
                    loader,
                ),
                "SEM_PLAYBACK_RECORD",
            )
        Reflect.findAndHookMethod(
            android.media.AudioRecord.Builder::class.java,
            "setAudioSource",
            Int::class.javaPrimitiveType,
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val scope = AudioScope.samsung.get()
                    if (scope == null || p!!.args!![0] as Int != playback) return
                    try {
                        val capture =
                            android.media.AudioPlaybackCaptureConfiguration.Builder(
                                    scope.projection!!
                                )
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        CallAudioCapture.configure(capture, scope.selection!!)
                        (p.thisObject as android.media.AudioRecord.Builder)
                            .setAudioPlaybackCaptureConfig(capture.build())
                        p.setResult(p.thisObject)
                        rt.report(
                            if (scope.selection.includeCalls)
                                "Recording playback including available call audio"
                            else "Recording media playback without call audio"
                        )
                    } catch (error: Throwable) {
                        p.setThrowable(
                            IllegalStateException("Playback audio filtering unavailable", error)
                        )
                        rt.fail(error)
                    }
                }
            },
        )
        // Enter Samsung's existing digital-mix branch only inside its audio-buffer callback.
        // Never change routing, preferred input, or the actual headphone connection state.
        val ownerClass =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.MP4Recorder",
                loader,
            )
        Reflect.findAndHookMethod(
            ownerClass,
            "isPlugging",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (SpeakerMixContext.includePlayback(p.thisObject)) p.setResult(true)
                }
            },
        )
        Reflect.findAndHookMethod(
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.EnhancedMicAudioRecorder",
                loader,
            ),
            "mixShortArrToByteBuffer",
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.recorder.core.AudioBuffer",
                loader,
            ),
            ByteBuffer::class.java,
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (
                        JavaBoolean.TRUE !=
                            Reflect.getAdditionalInstanceField(
                                p.thisObject,
                                AudioScope.SPEAKER_MEDIA,
                            )
                    )
                        return
                    try {
                        val owner = Reflect.getObjectField(p.thisObject!!, "pRecorder")
                        p.setObjectExtra("previousMixer", SpeakerMixContext.enter(owner))
                        p.setObjectExtra("speakerMedia", true)
                    } catch (error: Throwable) {
                        rt.fail(error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE == p.getObjectExtra("speakerMedia"))
                        SpeakerMixContext.restore(p.getObjectExtra("previousMixer"))
                }
            },
        )
    }

    @JvmStatic
    fun installAosp(rt: HookRuntime, loader: ClassLoader?) {
        Hooks.hookAllMethods(
            Reflect.findClass("com.android.systemui.screenrecord.ScreenMediaRecorder", loader),
            "start",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!rt.options!!.enabled) return
                    try {
                        AudioScope.aospScope.set(
                            AospRecordingUi.takeScope(p.thisObject, rt.options!!.appAudioOnly)
                        )
                    } catch (error: Throwable) {
                        p.setThrowable(IOException("Audio scope unavailable", error))
                        rt.fail(error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    AudioScope.aospScope.remove()
                }
            },
        )
        val recorder =
            Reflect.findClass(
                "com.android.systemui.screenrecord.ScreenInternalAudioRecorder",
                loader,
            )
        Hooks.hookAllConstructors(
            recorder,
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.setObjectExtra("previousAospAudio", AudioScope.aosp.get())
                    p.setObjectExtra("aospAudioEntered", true)
                    AudioScope.aosp.remove()
                    if (!rt.options!!.enabled) return
                    try {
                        val scope = AudioScope.aospScope.get()
                        val appOnly: Boolean =
                            if (scope == null) rt.options!!.appAudioOnly else scope
                        val uid: Int = if (appOnly) AudioScope.resolve(rt) else -1
                        AudioScope.aosp.set(
                            CallAudioPolicy.Selection(uid, rt.options!!.includeCallAudio)
                        )
                    } catch (error: Throwable) {
                        p.setThrowable(IOException("App audio filtering unavailable", error))
                        rt.fail(error)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE != p.getObjectExtra("aospAudioEntered")) return
                    val previous: CallAudioPolicy.Selection? =
                        p.getObjectExtra("previousAospAudio") as CallAudioPolicy.Selection?
                    if (previous == null) AudioScope.aosp.remove()
                    else AudioScope.aosp.set(previous)
                }
            },
        )
        Hooks.hookAllMethods(
            android.media.AudioPlaybackCaptureConfiguration.Builder::class.java,
            "build",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val selection = AudioScope.aosp.get()
                    if (selection != null)
                        CallAudioCapture.configure(
                            p.thisObject as android.media.AudioPlaybackCaptureConfiguration.Builder,
                            selection,
                        )
                }
            },
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun resolve(rt: HookRuntime): Int {
        val latch = CountDownLatch(1)
        val uid = intArrayOf(-2)
        TargetBridge.request(
            rt,
            "audio_uid",
            null,
            object : ResultReceiver(rt.worker) {
                override fun onReceiveResult(code: Int, result: Bundle?) {
                    if (code == Activity.RESULT_OK && result != null) {
                        val value = result.getInt("uid", -1)
                        if (value >= 0 || !result.getBoolean("app", false)) uid[0] = value
                    }
                    latch.countDown()
                }
            },
        )
        check(!(!latch.await(1500, TimeUnit.MILLISECONDS) || uid[0] == -2)) {
            "Audio target not resolved"
        }
        return uid[0]
    }

    @JvmRecord
    private data class SamsungAudio(
        val selection: CallAudioPolicy.Selection?,
        val projection: MediaProjection?,
    ) {
        override fun toString(): String =
            "SamsungAudio[selection=$selection, projection=$projection]"
    }
}
