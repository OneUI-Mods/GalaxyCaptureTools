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
    startForegroundService(
        new Intent(this, ToneService.class)
            .putExtra("voice", getIntent().getBooleanExtra("voice", false)));
    TextView text = new TextView(this);
    text.setText("INTERNAL TEST: other UID, media 880 Hz / optional voice 1100 Hz\nTap to stop");
    text.setTextSize(26);
    text.setOnClickListener(
        v -> {
          stopService(new Intent(this, ToneService.class));
          finish();
        });
    setContentView(text);
  }
}
