package dev.ryuya.galaxycapture

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/* Explicit internal device-test operations, absent from the distributed APK. */
open class SecureVolumeDriver {
    @Test
    open fun saveSettings() {
        val c = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val backup = c.getSharedPreferences("verification_backup", 0)
        assertFalse("Finish earlier verification first", backup.getBoolean("present", false))
        val all = JSONObject(c.getSharedPreferences("config", 0).getAll())
        assertTrue(
            backup.edit().putString("config", all.toString()).putBoolean("present", true).commit()
        )
    }

    @Test
    @Throws(Exception::class)
    open fun setSettings() {
        val c = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val values = JSONObject(InstrumentationRegistry.getArguments().getString("settings", "{}"))
        val edit = c.getSharedPreferences("config", 0).edit()
        for (toggle in CaptureSettings.all()) if (values.has(toggle!!.key))
            edit.putBoolean(toggle!!.key, values.getBoolean(toggle!!.key))
        for (key in arrayOf<String>("media_volume", "mic_volume")) if (values.has(key))
            edit.putInt(key, AudioLevels.clamp(values.getInt(key)))
        assertTrue(edit.commit())
        Startup.notifyHosts(c)
    }

    @Test
    @Throws(Exception::class)
    open fun restoreSettings() {
        val c = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val backup = c.getSharedPreferences("verification_backup", 0)
        assertTrue(backup.getBoolean("present", false))
        val values = JSONObject(backup.getString("config", "{}"))
        val edit = c.getSharedPreferences("config", 0).edit().clear()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = values.get(key)
            if (value is Boolean) edit.putBoolean(key, value)
            else if (value is Int) edit.putInt(key, value)
            else if (value is Long) edit.putLong(key, value)
            else if (value is String) edit.putString(key, value)
            else throw IllegalStateException("Unexpected setting type")
        }
        assertTrue(edit.commit())
        assertTrue(backup.edit().clear().commit())
        Startup.notifyHosts(c)
    }
}
