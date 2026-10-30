package dev.ryuya.galaxycapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.widget.TextView
import dev.ryuya.galaxycapture.hook.HookCallback
import dev.ryuya.galaxycapture.hook.HookCallback.MethodHookParam
import dev.ryuya.galaxycapture.hook.Hooks
import dev.ryuya.galaxycapture.hook.Reflect
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Relabel Samsung's acoustic speaker path without changing the selected sound option. */
object SamsungAudioUi {
    @JvmStatic
    fun install(rt: HookRuntime, loader: ClassLoader?) {
        val controller =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController",
                loader,
            )
        val audioUtil =
            Reflect.findClass(
                "com.samsung.android.app.smartcapture.screenrecorder.util.AudioUtil",
                loader,
            )
        Hooks.hookAllMethods(
            controller,
            "initializeRadioButtonsForNewLayout",
            object : HookCallback() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        val root: View? = p!!.args!![0] as View?
                        val host = root!!.getContext()
                        val id =
                            host
                                .getResources()
                                .getIdentifier(
                                    "radio_button_sound_all",
                                    "id",
                                    host.getPackageName(),
                                )
                        val choice = root!!.findViewById<View?>(id)
                        if (choice == null) return
                        val label: TextView? =
                            Reflect.getObjectField(choice, "textView") as TextView?
                        val original = label!!.getText()
                        val wrapping = MediaMicLabel(label!!, choice)
                        val strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
                        val audio = host.getSystemService<AudioManager?>(AudioManager::class.java)
                        val main = Handler(Looper.getMainLooper())
                        val refresh = Runnable {
                            try {
                                val output =
                                    (Reflect.callStaticMethod(
                                            audioUtil,
                                            "getCurrentAudioOutputType",
                                            host,
                                        ) as Number)
                                        .toInt()
                                val plugged: Boolean =
                                    Reflect.callStaticMethod(
                                        audioUtil,
                                        "checkCurrentAudioDevicePlugged",
                                        output,
                                    ) as Boolean
                                val micOnly: Boolean =
                                    rt.options!!.enabled &&
                                        !rt.options!!.speakerMedia &&
                                        !plugged &&
                                        !audio.isMicrophoneMute()
                                wrapping.update(
                                    if (micOnly) strings.getText(R.string.microphone_only)
                                    else original,
                                    rt.options!!.enabled,
                                )
                            } catch (error: Throwable) {
                                wrapping.update(original, false)
                                Hooks.log("GalaxyCapture: sound label unavailable: " + error)
                            }
                        }
                        val devices: AudioDeviceCallback =
                            object : AudioDeviceCallback() {
                                override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo?>?) {
                                    update()
                                }

                                override fun onAudioDevicesRemoved(
                                    removed: Array<AudioDeviceInfo?>?
                                ) {
                                    update()
                                }

                                fun update() {
                                    main.removeCallbacks(refresh)
                                    main.postDelayed(refresh, 150)
                                }
                            }
                        val route: BroadcastReceiver =
                            object : BroadcastReceiver() {
                                override fun onReceive(c: Context?, intent: Intent?) {
                                    main.post(refresh)
                                }
                            }
                        root!!.addOnAttachStateChangeListener(
                            object : OnAttachStateChangeListener {
                                private var devicesRegistered: Boolean = false
                                private var receiverRegistered: Boolean = false

                                override fun onViewAttachedToWindow(v: View) {
                                    try {
                                        audio.registerAudioDeviceCallback(devices, main)
                                        devicesRegistered = true
                                        val filter =
                                            IntentFilter(
                                                "android.media.STREAM_DEVICES_CHANGED_ACTION"
                                            )
                                        filter.addAction(
                                            AudioManager.ACTION_MICROPHONE_MUTE_CHANGED
                                        )
                                        host.registerReceiver(
                                            route,
                                            filter,
                                            Context.RECEIVER_NOT_EXPORTED,
                                        )
                                        receiverRegistered = true
                                        refresh.run()
                                    } catch (error: Throwable) {
                                        Hooks.log(
                                            "GalaxyCapture: sound route listener unavailable: " +
                                                error
                                        )
                                    }
                                }

                                override fun onViewDetachedFromWindow(v: View) {
                                    try {
                                        if (devicesRegistered)
                                            audio.unregisterAudioDeviceCallback(devices)
                                        if (receiverRegistered) host.unregisterReceiver(route)
                                    } catch (ignored: Throwable) {}
                                    receiverRegistered = false
                                    devicesRegistered = receiverRegistered
                                    main.removeCallbacks(refresh)
                                }
                            }
                        )
                        refresh.run()
                    } catch (error: Throwable) {
                        Hooks.log("GalaxyCapture: sound label unavailable: " + error)
                    }
                }
            },
        )
    }
}
