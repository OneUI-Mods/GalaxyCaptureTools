package dev.ryuya.galaxycapture

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/* Explicit internal driver; skipped in normal suites and absent from release APKs. */
open class CallAudioConfigTest {
    @Test
    @Throws(Exception::class)
    open fun configureFixtureRecording() {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("capture_audio_driver")
        Assume.assumeNotNull(action)
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val preferences = context.getSharedPreferences("config", 0)
        val backup = File(context.getFilesDir(), "call-audio-fixture-backup.json")
        when (action) {
            "snapshot" -> {
                assertFalse("Do not overwrite an unfinished test backup", backup.exists())
                val snapshot = JSONObject()
                for (entry in preferences.getAll().entries) {
                    val value = entry.value
                    assertTrue(
                        "Unknown preference type",
                        value is Boolean || value is Int || value is String,
                    )
                    snapshot.put(entry.key, value)
                }
                Files.writeString(backup.toPath(), snapshot.toString(), StandardCharsets.UTF_8)
            }
            "update" -> {
                assertTrue("Snapshot settings before testing", backup.isFile())
                val edit = preferences.edit()
                for (key in
                    arrayOf<String>(
                        "enabled",
                        "include_call_audio",
                        "app_audio_only",
                        "countdown",
                        "recording_volume",
                    )) {
                    if (!args.containsKey(key)) continue
                    val value = args.getString(key)
                    assertTrue("Boolean argument required", "true" == value || "false" == value)
                    edit.putBoolean(key, value.toBoolean())
                }
                for (key in arrayOf<String>("media_volume", "mic_volume")) if (
                    args.containsKey(key)
                )
                    edit.putInt(key, AudioLevels.clamp(args.getString(key)!!.toInt()))
                assertTrue(edit.commit())
                Startup.notifyHosts(context)
            }
            "restore" -> {
                assertTrue("Original settings backup is required", backup.isFile())
                val snapshot = JSONObject(Files.readString(backup.toPath(), StandardCharsets.UTF_8))
                val edit = preferences.edit().clear()
                val keys = snapshot.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = snapshot.get(key)
                    if (value is Boolean) edit.putBoolean(key, value)
                    else if (value is Int) edit.putInt(key, value)
                    else if (value is String) edit.putString(key, value)
                    else throw IllegalStateException("Unsupported backed-up setting type")
                }
                assertTrue(edit.commit())
                Startup.notifyHosts(context)
                assertTrue(backup.delete())
            }
            else -> fail("Unknown internal configuration action")
        }
    }
}
