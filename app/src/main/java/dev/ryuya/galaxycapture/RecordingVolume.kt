package dev.ryuya.galaxycapture

import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import java.lang.Boolean as JavaBoolean
import kotlin.jvm.JvmStatic

/* Only Samsung recorder PCM is affected. Device output and input routing stay native. */
object RecordingVolume {
    private const val LEVELS: String = "galaxycapture.audioLevels"
    private val mixedOwner: ThreadLocal<Any?> = ThreadLocal<Any?>()

    @JvmStatic
    fun install(rt: HookRuntime, loader: ClassLoader?) {
        val base: String = "com.samsung.android.app.smartcapture.screenrecorder.recorder.core."
        for (name in
            arrayOf<String>(
                "SystemAudioRecorder",
                "MicAudioRecorder",
                "EnhancedMicAudioRecorder",
            )) {
            val type = Reflect.findClass(base + name, loader)
            Hooks.hookAllMethods(
                type,
                "createAudioRecord",
                object : HookCallback() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        // A route change may rebuild AudioRecord on the same recorder instance.
                        if (
                            Reflect.getAdditionalInstanceField(
                                p.thisObject,
                                RecordingVolume.LEVELS,
                            ) == null
                        )
                            Reflect.setAdditionalInstanceField(
                                p.thisObject,
                                RecordingVolume.LEVELS,
                                AudioLevels.from(rt.options!!),
                            )
                    }
                },
            )
        }
        val enhanced = Reflect.findClass(base + "EnhancedMicAudioRecorder", loader)
        Hooks.hookAllMethods(
            enhanced,
            "mixShortArrToByteBuffer",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val levels = RecordingVolume.levels(rt, p.thisObject)
                    if (levels == null) return
                    try {
                        val buffer: Any? = p!!.args!![0]
                        val data: Array<ShortArray?>? =
                            Reflect.getObjectField(buffer!!, "shortDataArr") as Array<ShortArray?>?
                        val valid: IntArray? =
                            Reflect.getObjectField(buffer!!, "readSize") as IntArray?
                        AudioLevels.scale(data!![0]!!, valid!![0], levels.mic)
                        AudioLevels.scale(data!![1]!!, valid!![1], levels.media)
                        p.setObjectExtra("previousVolumeOwner", RecordingVolume.mixedOwner.get())
                        p.setObjectExtra("volumeEntered", true)
                        RecordingVolume.mixedOwner.set(
                            Reflect.getObjectField(p.thisObject!!, "pRecorder")
                        )
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }

                override fun afterHookedMethod(p: MethodHookParam) {
                    if (JavaBoolean.TRUE != p.getObjectExtra("volumeEntered")) return
                    val previous = p.getObjectExtra("previousVolumeOwner")
                    if (previous == null) RecordingVolume.mixedOwner.remove()
                    else RecordingVolume.mixedOwner.set(previous)
                }
            },
        )
        // Samsung otherwise applies the device playback-volume table to the digital stream.
        // Replace it only inside this recorder's mix callback when independent levels are enabled.
        Hooks.hookAllMethods(
            Reflect.findClass(base + "MP4Recorder", loader),
            "getAudioScale",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (p.thisObject === RecordingVolume.mixedOwner.get() && rt.guard.allowed())
                        p.setResult(1f)
                }
            },
        )
        Hooks.hookAllMethods(
            Reflect.findClass(base + "BaseAudioRecorder", loader),
            "putShortArrToByteBuffer",
            object : HookCallback() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val levels = RecordingVolume.levels(rt, p.thisObject)
                    if (levels == null) return
                    val name = p.thisObject!!.javaClass.getSimpleName()
                    if (name != "SystemAudioRecorder" && name != "MicAudioRecorder") return
                    try {
                        val data: Array<ShortArray?>? =
                            Reflect.getObjectField(p!!.args!![0]!!, "shortDataArr")
                                as Array<ShortArray?>?
                        val valid: IntArray? =
                            Reflect.getObjectField(p!!.args!![0]!!, "readSize") as IntArray?
                        AudioLevels.scale(
                            data!![0]!!,
                            valid!![0],
                            if (name == "SystemAudioRecorder") levels.media else levels.mic,
                        )
                    } catch (e: Throwable) {
                        rt.fail(e)
                    }
                }
            },
        )
        SamsungSettingsUi.install(rt, loader!!)
    }

    @JvmStatic
    private fun levels(rt: HookRuntime, recorder: Any?): AudioLevels? {
        val value: AudioLevels? =
            Reflect.getAdditionalInstanceField(recorder, RecordingVolume.LEVELS) as AudioLevels?
        return if (value != null && value.enabled && rt.guard.allowed()) value else null
    }
}
