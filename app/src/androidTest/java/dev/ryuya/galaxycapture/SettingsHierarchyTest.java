package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.Test;

public class SettingsHierarchyTest {
  @Test
  public void masterVisiblyDisablesAllIndividualControlsAndRestoresTheirValues() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var prefs = context.getSharedPreferences("config", 0);
    boolean original = prefs.getBoolean("enabled", false);
    try (var activity = ActivityScenario.launch(MainActivity.class)) {
      activity.onActivity(
          a -> {
            MaterialSwitch master =
                find(a.getWindow().getDecorView(), a.getString(R.string.enabled));
            assertNotNull(master);
            if (master.isChecked()) master.performClick();
            for (int label :
                new int[] {
                  R.string.rotation,
                  R.string.ignore_half_turns,
                  R.string.samsung_app,
                  R.string.single_window,
                  R.string.app_audio_only
                }) {
              MaterialSwitch control = find(a.getWindow().getDecorView(), a.getString(label));
              assertNotNull(control);
              assertFalse(control.isEnabled());
            }
            boolean saved = prefs.getBoolean("rotation", true);
            master.performClick();
            assertEquals(
                saved,
                find(a.getWindow().getDecorView(), a.getString(R.string.rotation)).isChecked());
            assertTrue(
                find(a.getWindow().getDecorView(), a.getString(R.string.ignore_half_turns))
                    .isEnabled());
            if (master.isChecked() != original) master.performClick();
          });
    } finally {
      prefs.edit().putBoolean("enabled", original).commit();
    }
  }

  private static MaterialSwitch find(View root, String text) {
    if (root instanceof MaterialSwitch s && s.getText().toString().equals(text)) return s;
    if (root instanceof ViewGroup g)
      for (int i = 0; i < g.getChildCount(); i++) {
        MaterialSwitch r = find(g.getChildAt(i), text);
        if (r != null) return r;
      }
    return null;
  }
}
