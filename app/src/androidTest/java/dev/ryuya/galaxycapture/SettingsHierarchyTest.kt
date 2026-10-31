package dev.ryuya.galaxycapture

import android.content.Intent
import android.view.*
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

open class SettingsHierarchyTest {
    @Test
    open fun masterVisiblyDisablesAllIndividualControlsAndRestoresTheirValues() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val prefs = context.getSharedPreferences("config", 0)
        val original = prefs.getBoolean("enabled", false)
        val originalRotation = prefs.getBoolean("rotation", true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity({ a ->
                    val master =
                        SettingsHierarchyTest.find(
                            a.getWindow().getDecorView(),
                            a.getString(R.string.enabled),
                        )
                    assertNotNull(master)
                    if (master!!.isChecked()) master!!.performClick()
                    for (label in
                        intArrayOf(
                            R.string.rotation,
                            R.string.ignore_half_turns,
                            R.string.samsung_app,
                            R.string.single_window,
                            R.string.countdown,
                            R.string.app_audio_only,
                            R.string.speaker_media,
                            R.string.hide_duplicate_indicator,
                            R.string.exclude_capture_controls,
                            R.string.hide_recording_controls,
                            R.string.secure_recording,
                            R.string.secure_screenshot,
                            R.string.recording_volume,
                        )) {
                        val control =
                            SettingsHierarchyTest.find(
                                a.getWindow().getDecorView(),
                                a.getString(label),
                            )
                        assertNotNull(control)
                        assertFalse(control!!.isEnabled())
                    }
                    val saved = prefs.getBoolean("rotation", true)
                    master!!.performClick()
                    assertEquals(
                        saved,
                        SettingsHierarchyTest.find(
                                a.getWindow().getDecorView(),
                                a.getString(R.string.rotation),
                            )!!
                            .isChecked(),
                    )
                    assertTrue(
                        SettingsHierarchyTest.find(
                                a.getWindow().getDecorView(),
                                a.getString(R.string.ignore_half_turns),
                            )!!
                            .isEnabled()
                    )
                    val rotation =
                        SettingsHierarchyTest.find(
                            a.getWindow().getDecorView(),
                            a.getString(R.string.rotation),
                        )
                    if (rotation!!.isChecked()) rotation!!.performClick()
                    assertTrue(
                        SettingsHierarchyTest.find(
                                a.getWindow().getDecorView(),
                                a.getString(R.string.ignore_half_turns),
                            )!!
                            .isEnabled()
                    )
                    if (rotation!!.isChecked() != originalRotation) rotation!!.performClick()
                    if (master!!.isChecked() != original) master!!.performClick()
                })
            }
        } finally {
            prefs
                .edit()
                .putBoolean("enabled", original)
                .putBoolean("rotation", originalRotation)
                .commit()
            for (pkg in
                arrayOf<String>(
                    "android",
                    "com.android.systemui",
                    "com.samsung.android.app.smartcapture",
                )) context.sendBroadcast(Intent(HookRuntime.CONFIG_CHANGED).setPackage(pkg))
        }
    }

    companion object {
        @JvmStatic
        private fun find(root: View?, text: String): MaterialSwitch? {
            if (
                root is MaterialSwitch &&
                    (root.getText().toString() == text ||
                        text == root.getContentDescription()?.toString())
            )
                return root
            if (root is ViewGroup)
                for (i in 0 until root.getChildCount()) {
                    val r = SettingsHierarchyTest.find(root.getChildAt(i), text)
                    if (r != null) return r
                }
            return null
        }
    }
}
