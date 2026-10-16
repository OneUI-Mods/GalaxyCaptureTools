package dev.ryuya.galaxycapture;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

public final class CaptureApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    DynamicColors.applyToActivitiesIfAvailable(this);
  }
}
