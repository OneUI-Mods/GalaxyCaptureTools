package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;

/** Explicit internal device-test operations, absent from the distributed APK. */
public class SecureVolumeDriver {
  @Test public void saveSettings() {
    var c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var backup = c.getSharedPreferences("verification_backup", 0);
    assertFalse("Finish earlier verification first", backup.getBoolean("present", false));
    var all = new JSONObject(c.getSharedPreferences("config", 0).getAll());
    assertTrue(backup.edit().putString("config", all.toString()).putBoolean("present", true).commit());
  }
  @Test public void setSettings() throws Exception {
    var c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    JSONObject values = new JSONObject(InstrumentationRegistry.getArguments().getString("settings", "{}"));
    var edit = c.getSharedPreferences("config", 0).edit();
    for (var toggle : CaptureSettings.all())
      if (values.has(toggle.key())) edit.putBoolean(toggle.key(), values.getBoolean(toggle.key()));
    for (String key : new String[] {"media_volume", "mic_volume"})
      if (values.has(key)) edit.putInt(key, AudioLevels.clamp(values.getInt(key)));
    assertTrue(edit.commit());
    Startup.notifyHosts(c);
  }
  @Test public void restoreSettings() throws Exception {
    var c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var backup = c.getSharedPreferences("verification_backup", 0);
    assertTrue(backup.getBoolean("present", false));
    JSONObject values = new JSONObject(backup.getString("config", "{}"));
    SharedPreferences.Editor edit = c.getSharedPreferences("config", 0).edit().clear();
    for (var keys = values.keys(); keys.hasNext();) {
      String key = keys.next(); Object value = values.get(key);
      if (value instanceof Boolean b) edit.putBoolean(key, b);
      else if (value instanceof Integer n) edit.putInt(key, n);
      else if (value instanceof Long n) edit.putLong(key, n);
      else if (value instanceof String s) edit.putString(key, s);
      else throw new IllegalStateException("Unexpected setting type");
    }
    assertTrue(edit.commit());
    assertTrue(backup.edit().clear().commit());
    Startup.notifyHosts(c);
  }
}
