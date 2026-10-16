package dev.ryuya.captureaudiofixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Separate UID, used only to detect unwanted audio in an app-scoped recording. */
public class ToneActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    startForegroundService(new Intent(this, ToneService.class));
    TextView text = new TextView(this);
    text.setText("INTERNAL TEST: other app, 880 Hz, 60 seconds\nTap to stop");
    text.setTextSize(26);
    text.setOnClickListener(
        v -> {
          stopService(new Intent(this, ToneService.class));
          finish();
        });
    setContentView(text);
  }
}
