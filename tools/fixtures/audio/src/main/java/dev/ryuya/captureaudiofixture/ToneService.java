package dev.ryuya.captureaudiofixture;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.*;

public class ToneService extends Service {
  private volatile boolean running;

  @Override
  public void onCreate() {
    super.onCreate();
    var notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(
        new NotificationChannel("test", "Internal audio test", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        1,
        new Notification.Builder(this, "test")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Internal audio test: 880 Hz")
            .build());
    running = true;
    new Thread(
            () -> {
              AudioTrack track =
                  new AudioTrack.Builder()
                      .setAudioAttributes(
                          new AudioAttributes.Builder()
                              .setUsage(AudioAttributes.USAGE_MEDIA)
                              .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                              .build())
                      .setAudioFormat(
                          new AudioFormat.Builder()
                              .setSampleRate(48000)
                              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                              .build())
                      .setBufferSizeInBytes(19200)
                      .setTransferMode(AudioTrack.MODE_STREAM)
                      .build();
              try {
                track.play();
                short[] samples = new short[4800];
                long end = SystemClock.elapsedRealtime() + 60000, frame = 0;
                while (running && SystemClock.elapsedRealtime() < end) {
                  for (int i = 0; i < samples.length; i++, frame++)
                    samples[i] = (short) (1400 * Math.sin(2 * Math.PI * 880 * frame / 48000));
                  if (track.write(samples, 0, samples.length) < 0) break;
                }
              } finally {
                track.stop();
                track.release();
                stopSelf();
              }
            },
            "InternalOtherAppTone")
        .start();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    running = false;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
