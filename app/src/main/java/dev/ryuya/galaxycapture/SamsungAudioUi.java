package dev.ryuya.galaxycapture;

import android.content.*;
import android.media.*;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.*;

/** Relabel Samsung's acoustic speaker path without changing the selected sound option. */
final class SamsungAudioUi {
  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> controller = XposedHelpers.findClass(
        "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", loader);
    Class<?> audioUtil = XposedHelpers.findClass(
        "com.samsung.android.app.smartcapture.screenrecorder.util.AudioUtil", loader);
    XposedBridge.hookAllMethods(
        controller, "initializeRadioButtonsForNewLayout", new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            try {
              View root = (View) p.args[0];
              Context host = root.getContext();
              int id = host.getResources().getIdentifier(
                  "radio_button_sound_all", "id", host.getPackageName());
              View choice = root.findViewById(id);
              if (choice == null)
                return;
              TextView label = (TextView) XposedHelpers.getObjectField(choice, "textView");
              CharSequence original = label.getText();
              Context strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
              AudioManager audio = host.getSystemService(AudioManager.class);
              Handler main = new Handler(Looper.getMainLooper());
              Runnable refresh = () -> {
                try {
                  int output = ((Number) XposedHelpers.callStaticMethod(
                                    audioUtil, "getCurrentAudioOutputType", host))
                                   .intValue();
                  boolean plugged = (boolean) XposedHelpers.callStaticMethod(
                      audioUtil, "checkCurrentAudioDevicePlugged", output);
                  boolean micOnly = rt.options.enabled() && !rt.options.mixAppAudioWithMic()
                      && !plugged && !audio.isMicrophoneMute();
                  label.setText(micOnly ? strings.getText(R.string.microphone_only) : original);
                } catch (Throwable error) {
                  label.setText(original);
                  XposedBridge.log("GalaxyCapture: sound label unavailable: " + error);
                }
              };
              AudioDeviceCallback devices = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                  update();
                }
                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                  update();
                }
                private void update() {
                  main.removeCallbacks(refresh);
                  main.postDelayed(refresh, 150);
                }
              };
              BroadcastReceiver route = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                  main.post(refresh);
                }
              };
              root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                private boolean devicesRegistered, receiverRegistered;
                @Override
                public void onViewAttachedToWindow(View v) {
                  try {
                    audio.registerAudioDeviceCallback(devices, main);
                    devicesRegistered = true;
                    IntentFilter filter =
                        new IntentFilter("android.media.STREAM_DEVICES_CHANGED_ACTION");
                    filter.addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);
                    host.registerReceiver(route, filter, Context.RECEIVER_NOT_EXPORTED);
                    receiverRegistered = true;
                    refresh.run();
                  } catch (Throwable error) {
                    XposedBridge.log("GalaxyCapture: sound route listener unavailable: " + error);
                  }
                }
                @Override
                public void onViewDetachedFromWindow(View v) {
                  try {
                    if (devicesRegistered)
                      audio.unregisterAudioDeviceCallback(devices);
                    if (receiverRegistered)
                      host.unregisterReceiver(route);
                  } catch (Throwable ignored) {
                  }
                  devicesRegistered = receiverRegistered = false;
                  main.removeCallbacks(refresh);
                }
              });
              refresh.run();
            } catch (Throwable error) {
              XposedBridge.log("GalaxyCapture: sound label unavailable: " + error);
            }
          }
        });
  }
}
