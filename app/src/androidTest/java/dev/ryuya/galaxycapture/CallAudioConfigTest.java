package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/** Explicit internal driver; skipped in normal suites and absent from release APKs. */
public class CallAudioConfigTest {
  @Test
  public void configureFixtureRecording() throws Exception {
    var args = InstrumentationRegistry.getArguments();
    String action = args.getString("capture_audio_driver");
    Assume.assumeNotNull(action);
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var preferences = context.getSharedPreferences("config", 0);
    File backup = new File(context.getFilesDir(), "call-audio-fixture-backup.json");
    switch (action) {
      case "snapshot" -> {
        assertFalse("Do not overwrite an unfinished test backup", backup.exists());
        JSONObject snapshot = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
          Object value = entry.getValue();
          assertTrue(
              "Unknown preference type",
              value instanceof Boolean || value instanceof Integer || value instanceof String);
          snapshot.put(entry.getKey(), value);
        }
        Files.writeString(backup.toPath(), snapshot.toString(), StandardCharsets.UTF_8);
      }
      case "update" -> {
        assertTrue("Snapshot settings before testing", backup.isFile());
        var edit = preferences.edit();
        for (String key :
            new String[] {
              "enabled", "include_call_audio", "app_audio_only", "countdown", "recording_volume"
            }) {
          if (!args.containsKey(key)) continue;
          String value = args.getString(key);
          assertTrue("Boolean argument required", "true".equals(value) || "false".equals(value));
          edit.putBoolean(key, Boolean.parseBoolean(value));
        }
        for (String key : new String[] {"media_volume", "mic_volume"})
          if (args.containsKey(key))
            edit.putInt(key, AudioLevels.clamp(Integer.parseInt(args.getString(key))));
        assertTrue(edit.commit());
        Startup.notifyHosts(context);
      }
      case "restore" -> {
        assertTrue("Original settings backup is required", backup.isFile());
        JSONObject snapshot =
            new JSONObject(Files.readString(backup.toPath(), StandardCharsets.UTF_8));
        var edit = preferences.edit().clear();
        for (var keys = snapshot.keys(); keys.hasNext(); ) {
          String key = keys.next();
          Object value = snapshot.get(key);
          if (value instanceof Boolean b) edit.putBoolean(key, b);
          else if (value instanceof Integer n) edit.putInt(key, n);
          else if (value instanceof String text) edit.putString(key, text);
          else throw new IllegalStateException("Unsupported backed-up setting type");
        }
        assertTrue(edit.commit());
        Startup.notifyHosts(context);
        assertTrue(backup.delete());
      }
      default -> fail("Unknown internal configuration action");
    }
  }
}
