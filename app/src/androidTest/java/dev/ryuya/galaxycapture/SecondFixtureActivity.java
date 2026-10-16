package dev.ryuya.galaxycapture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

public class SecondFixtureActivity extends Activity {
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    var v = new TextView(this);
    v.setText("WINDOW B — MUST NOT APPEAR IN WINDOW-LOCKED RECORDING");
    v.setTextColor(Color.WHITE);
    v.setTextSize(36);
    v.setBackgroundColor(Color.RED);
    setContentView(v);
  }
}
