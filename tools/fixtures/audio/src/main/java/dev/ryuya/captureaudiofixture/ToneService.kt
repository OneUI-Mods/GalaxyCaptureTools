package dev.ryuya.captureaudiofixture

import android.app.Notification.Builder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.ryuya.fixtureaudio.TonePlayback

/* Other UID: media at 880 Hz, with optional simulated voice playback at 1100 Hz. */
open class ToneService : Service() {
    private var media: TonePlayback? = null
    private var voice: TonePlayback? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val stop: Runnable = Runnable { this.stopSelf() }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService<NotificationManager?>(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel("test", "Internal audio test", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            1,
            Builder(this, "test")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Internal audio test")
                .build(),
        )
        media = TonePlayback(AudioAttributes.USAGE_MEDIA, 880)
        handler.postDelayed(stop, 60000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (voice == null && intent != null && intent.getBooleanExtra("voice", false))
            voice = TonePlayback(AudioAttributes.USAGE_VOICE_COMMUNICATION, 1100)
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stop)
        if (media != null) media!!.close()
        if (voice != null) voice!!.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
