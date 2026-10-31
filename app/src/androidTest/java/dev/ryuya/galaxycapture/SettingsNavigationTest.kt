package dev.ryuya.galaxycapture

import android.view.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.jvm.JvmStatic
import org.junit.Assert.*
import org.junit.Test

/* Navigation must preserve settings and hidden-page positions across Activity recreation. */
open class SettingsNavigationTest {
    @Test
    open fun recorderScopesSeparateControlsWhileMasterStaysVisible() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val before = context.getSharedPreferences("config", 0).getAll()
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            screen.onActivity({ a ->
                val root = a.getWindow().getDecorView()
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(a.getString(R.string.tab_common), tabs.getTabAt(0)!!.getText())
                assertEquals(a.getString(R.string.tab_galaxy), tabs.getTabAt(1)!!.getText())
                val master = SettingsNavigationTest.toggle(root, a.getString(R.string.enabled))
                assertTrue(master!!.isShown())
                for (text in
                    intArrayOf(
                        R.string.rotation,
                        R.string.ignore_half_turns,
                        R.string.single_window,
                        R.string.secure_recording,
                        R.string.app_audio_only,
                        R.string.hide_duplicate_indicator,
                    )) assertTrue(
                    SettingsNavigationTest.toggle(root, a.getString(text))!!.isShown()
                )
                assertFalse(
                    SettingsNavigationTest.toggle(root, a.getString(R.string.speaker_media))!!
                        .isShown()
                )
                tabs.getTabAt(1)!!.select()
                assertTrue(master!!.isShown())
                assertFalse(
                    SettingsNavigationTest.toggle(root, a.getString(R.string.rotation))!!.isShown()
                )
                assertFalse(
                    SettingsNavigationTest.toggle(root, a.getString(R.string.app_audio_only))!!
                        .isShown()
                )
                for (text in
                    intArrayOf(
                        R.string.samsung_app,
                        R.string.countdown,
                        R.string.speaker_media,
                        R.string.recording_volume,
                        R.string.secure_screenshot,
                        R.string.exclude_capture_controls,
                        R.string.hide_recording_controls,
                    )) assertTrue(
                    SettingsNavigationTest.toggle(root, a.getString(text))!!.isShown()
                )
            })
        }
        assertEquals(before, context.getSharedPreferences("config", 0).getAll())
    }

    @Test
    open fun tabsRestoreSelectionAndPagePositionWithoutChangingSettings() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        val before = context.getSharedPreferences("config", 0).getAll()
        val scroll = intArrayOf(0)
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(4, tabs.getTabCount())
                assertEquals(0, tabs.getSelectedTabPosition())
                val page =
                    SettingsNavigationTest.shown(
                        a.getWindow().getDecorView(),
                        NestedScrollView::class.java,
                    )
                assertNotNull(page)
                page!!.scrollTo(0, 100000)
                scroll[0] = page!!.getScrollY()
                tabs.getTabAt(3)!!.select()
            })
            screen.recreate()
            screen.onActivity({ a ->
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(3, tabs.getSelectedTabPosition())
                tabs.getTabAt(0)!!.select()
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                assertEquals(
                    scroll[0],
                    SettingsNavigationTest.shown(
                            a.getWindow().getDecorView(),
                            NestedScrollView::class.java,
                        )!!
                        .getScrollY(),
                )
            })
            assertEquals(before, context.getSharedPreferences("config", 0).getAll())
        }
    }

    @Test
    open fun licenseSearchDetailsAndBackKeepTheQueryAndLicensePosition() {
        val scroll = intArrayOf(0)
        ActivityScenario.launch(LicensesActivity::class.java).use { screen ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val search: com.google.android.material.textfield.TextInputEditText =
                    a.findViewById(R.id.license_search)
                search.setText("material")
                val list: ListView = a.findViewById(R.id.license_list)
                assertEquals(1, list.getAdapter().getCount())
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val list: ListView = a.findViewById(R.id.license_list)
                assertNotNull(list.getChildAt(0))
                list.getChildAt(0).performClick()
                val license: TextView = a.findViewById(R.id.license_text)
                assertTrue(license.getText().toString().contains("TERMS AND CONDITIONS"))
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                val page =
                    SettingsNavigationTest.shown(
                        a.getWindow().getDecorView(),
                        NestedScrollView::class.java,
                    )
                page!!.scrollTo(0, 100000)
                scroll[0] = page!!.getScrollY()
                assertTrue(scroll[0] > 0)
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(2, tabs.getTabCount())
                tabs.getTabAt(1)!!.select()
            })
            screen.recreate()
            screen.onActivity({ a ->
                val tabs: com.google.android.material.tabs.TabLayout =
                    a.findViewById(R.id.settings_tabs)
                assertEquals(1, tabs.getSelectedTabPosition())
                tabs.getTabAt(0)!!.select()
            })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screen.onActivity({ a ->
                assertEquals(
                    scroll[0],
                    SettingsNavigationTest.shown(
                            a.getWindow().getDecorView(),
                            NestedScrollView::class.java,
                        )!!
                        .getScrollY(),
                )
                a.onBackPressedDispatcher.onBackPressed()
                val search: com.google.android.material.textfield.TextInputEditText =
                    a.findViewById(R.id.license_search)
                assertEquals("material", search.getText().toString())
                val list: ListView = a.findViewById(R.id.license_list)
                assertEquals(1, list.getAdapter().getCount())
                search.setText("no-such-library-829403")
                assertEquals(0, list.getAdapter().getCount())
            })
        }
    }

    companion object {
        @JvmStatic
        private fun <T : View?> shown(node: View, type: Class<T>): T? {
            if (!node.isShown()) return null
            if (type.isInstance(node)) return type.cast(node)
            if (node is ViewGroup)
                for (i in 0 until node.getChildCount()) {
                    val found = SettingsNavigationTest.shown(node.getChildAt(i), type)
                    if (found != null) return found
                }
            return null
        }

        @JvmStatic
        private fun toggle(root: View?, text: String): MaterialSwitch? {
            if (
                root is MaterialSwitch &&
                    (root.getText().toString() == text ||
                        text == root.getContentDescription()?.toString())
            )
                return root
            if (root is ViewGroup)
                for (i in 0 until root.getChildCount()) {
                    val result = SettingsNavigationTest.toggle(root.getChildAt(i), text)
                    if (result != null) return result
                }
            return null
        }
    }
}
