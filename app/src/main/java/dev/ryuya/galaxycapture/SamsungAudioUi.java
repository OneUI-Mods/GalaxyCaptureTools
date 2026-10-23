package dev.ryuya.galaxycapture;

import android.content.*;
import android.media.*;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import dev.ryuya.galaxycapture.hook.*;

/** Relabel Samsung's acoustic speaker path without changing the selected sound option. */
final class SamsungAudioUi {
  static void install(HookRuntime rt, ClassLoader loader) {
    Class<?> controller =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", loader);
    Class<?> audioUtil =
        Reflect.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.util.AudioUtil", loader);
    Hooks.hookAllMethods(
        controller,
        "initializeRadioButtonsForNewLayout",
        new HookCallback() {
          @Override
          protected void afterHookedMethod(MethodHookParam p) {
            try {
              View root = (View) p.args[0];
              Context host = root.getContext();
              int id =
                  host.getResources()
                      .getIdentifier("radio_button_sound_all", "id", host.getPackageName());
              View choice = root.findViewById(id);
              if (choice == null) return;
              TextView label = (TextView) Reflect.getObjectField(choice, "textView");
              CharSequence original = label.getText();
              MediaMicLabel wrapping = new MediaMicLabel(label, choice);
              Context strings = host.createPackageContext(BuildConfig.APPLICATION_ID, 0);
              AudioManager audio = host.getSystemService(AudioManager.class);
              Handler main = new Handler(Looper.getMainLooper());
              Runnable refresh =
                  () -> {
                    try {
                      int output =
                          ((Number)
                                  Reflect.callStaticMethod(
                                      audioUtil, "getCurrentAudioOutputType", host))
                              .intValue();
                      boolean plugged =
                          (boolean)
                              Reflect.callStaticMethod(
                                  audioUtil, "checkCurrentAudioDevicePlugged", output);
                      boolean micOnly =
                          rt.options.enabled()
                              && !rt.options.speakerMedia()
                              && !plugged
                              && !audio.isMicrophoneMute();
                      wrapping.update(
                          micOnly ? strings.getText(R.string.microphone_only) : original,
                          rt.options.enabled());
                    } catch (Throwable error) {
                      wrapping.update(original, false);
                      Hooks.log("GalaxyCapture: sound label unavailable: " + error);
                    }
                  };
              AudioDeviceCallback devices =
                  new AudioDeviceCallback() {
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
              BroadcastReceiver route =
                  new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                      main.post(refresh);
                    }
                  };
              root.addOnAttachStateChangeListener(
                  new View.OnAttachStateChangeListener() {
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
                        Hooks.log("GalaxyCapture: sound route listener unavailable: " + error);
                      }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                      try {
                        if (devicesRegistered) audio.unregisterAudioDeviceCallback(devices);
                        if (receiverRegistered) host.unregisterReceiver(route);
                      } catch (Throwable ignored) {
                      }
                      devicesRegistered = receiverRegistered = false;
                      main.removeCallbacks(refresh);
                    }
                  });
              refresh.run();
            } catch (Throwable error) {
              Hooks.log("GalaxyCapture: sound label unavailable: " + error);
            }
          }
        });
  }
}
