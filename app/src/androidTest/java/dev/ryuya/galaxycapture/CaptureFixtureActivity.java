package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;

/** Internal test APK only. A new Activity must never leak into a window-locked recording. */
public class CaptureFixtureActivity extends Activity {
  private android.media.AudioTrack tone;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    if (b != null) getIntent().putExtra("rotation", b.getInt("rotation", 0));
    apply(getIntent());
    if (getIntent().getBooleanExtra("tone", false)) {
      tone =
          new android.media.AudioTrack.Builder()
              .setAudioAttributes(
                  new android.media.AudioAttributes.Builder()
                      .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                      .setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                      .build())
              .setAudioFormat(
                  new android.media.AudioFormat.Builder()
                      .setSampleRate(48000)
                      .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                      .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(96000)
              .setTransferMode(android.media.AudioTrack.MODE_STATIC)
              .build();
      short[] samples = new short[48000];
      for (int n = 0; n < samples.length; n++)
        samples[n] = (short) (1400 * Math.sin(2 * Math.PI * 440 * n / 48000));
      tone.write(samples, 0, samples.length);
      tone.setLoopPoints(0, samples.length, -1);
      tone.play();
    }
  }

  @Override
  protected void onDestroy() {
    if (tone != null) {
      tone.stop();
      tone.release();
      tone = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putInt("rotation", getIntent().getIntExtra("rotation", 0));
    super.onSaveInstanceState(out);
  }

  @Override
  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    apply(i);
  }

  private void apply(Intent i) {
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (i.getBooleanExtra("secure", false))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    if (i.hasExtra("rotation"))
      setRequestedOrientation(
          switch (i.getIntExtra("rotation", 0)) {
            case 1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case 2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case 3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
          });
    setContentView(
        new View(this) {
          final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

          @Override
          protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int[] colors = {0xffe4ce41, 0xff2980df, 0xff26bf77, 0xffb547d5};
            for (int n = 0; n < 4; n++) {
              float x = (n % 2) * w / 2, y = (n / 2) * h / 2;
              p.setColor(colors[n]);
              c.drawRect(x, y, x + w / 2, y + h / 2, p);
              p.setColor(Color.WHITE);
              p.setTextSize(Math.min(w, h) / 9);
              c.drawText("WINDOW A " + n, x + 25, y + h / 4, p);
            }
            p.setColor(Color.WHITE);
            p.setTextSize(50);
            c.drawText("Tap: open WINDOW B", 30, h - 80, p);
          }

          @Override
          public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP && getIntent().getBooleanExtra("interactive", true))
              startActivity(new Intent(CaptureFixtureActivity.this, SecondFixtureActivity.class));
            return true;
          }
        });
  }
}
