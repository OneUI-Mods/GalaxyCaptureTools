package dev.ryuya.captureaudiofixture;

import android.app.*;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.*;
import dev.ryuya.fixtureaudio.TonePlayback;

/** Other UID: media at 880 Hz, with optional simulated voice playback at 1100 Hz. */
public class ToneService extends Service {
  private TonePlayback media, voice;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable stop = this::stopSelf;

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
            .setContentTitle("Internal audio test")
            .build());
    media = new TonePlayback(AudioAttributes.USAGE_MEDIA, 880);
    handler.postDelayed(stop, 60000);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (voice == null && intent != null && intent.getBooleanExtra("voice", false))
      voice = new TonePlayback(AudioAttributes.USAGE_VOICE_COMMUNICATION, 1100);
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacks(stop);
    if (media != null) media.close();
    if (voice != null) voice.close();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
