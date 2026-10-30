package dev.ryuya.galaxycapture

import android.app.UiAutomation
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.SystemClock
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.function.Predicate
import org.junit.Assert.*
import org.junit.Test

/* Exercises the Samsung dialog itself, including cancelling after a confirmed target. */
open class NativePickerNavigationTest {
    private val ui: UiAutomation? = InstrumentationRegistry.getInstrumentation().getUiAutomation()
    private val strings: Context? = InstrumentationRegistry.getInstrumentation().getTargetContext()

    @Test
    open fun separatePickerSupportsRecentsScrollingSearchAndBackWithoutLosingTarget() {
        assertNull(
            find(
                Predicate { n: AccessibilityNodeInfo? ->
                    "android.widget.EditText".contentEquals(n!!.getClassName())
                }
            )
        )
        click(text(R.string.area_app)!!)
        await(text(R.string.choose_app)!!)
        await(
            Predicate { n: AccessibilityNodeInfo? ->
                "android.widget.ListView".contentEquals(n!!.getClassName()) &&
                    n!!.getChildCount() > 0
            }
        )
        assertNotNull(find(text(R.string.recent_apps)!!))
        val list =
            find(
                Predicate { n: AccessibilityNodeInfo? ->
                    "android.widget.ListView".contentEquals(n!!.getClassName())
                }
            )
        assertTrue("Recent apps should scroll", list!!.isScrollable())
        assertTrue(list!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        click(text(R.string.other_apps)!!)
        await(text(R.string.all_apps)!!)
        val edit =
            await(
                Predicate { n: AccessibilityNodeInfo? ->
                    "android.widget.EditText".contentEquals(n!!.getClassName())
                }
            )
        val query = Bundle()
        query.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "dev.ryuya.galaxycapture",
        )
        assertTrue(edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, query))
        await(text(R.string.search_results)!!)
        val label = strings!!.getString(R.string.app_name)
        click(Predicate { n: AccessibilityNodeInfo? -> TextUtils.equals(label, n!!.getText()) })
        val summary: String = strings!!.getString(R.string.selected_target, label) + "  ›"
        await(Predicate { n: AccessibilityNodeInfo? -> TextUtils.equals(summary, n!!.getText()) })
        assertNull(
            find(
                Predicate { n: AccessibilityNodeInfo? ->
                    "android.widget.EditText".contentEquals(n!!.getClassName())
                }
            )
        )
        assertTrue(
            find(
                    Predicate { n: AccessibilityNodeInfo? ->
                        "android:id/button1" == n!!.getViewIdResourceName()
                    }
                )!!
                .isEnabled()
        )
        click(Predicate { n: AccessibilityNodeInfo? -> TextUtils.equals(summary, n!!.getText()) })
        await(text(R.string.choose_app)!!)
        click(text(R.string.back_to_recording)!!)
        await(Predicate { n: AccessibilityNodeInfo? -> TextUtils.equals(summary, n!!.getText()) })
        assertTrue(
            find(
                    Predicate { n: AccessibilityNodeInfo? ->
                        "android:id/button1" == n!!.getViewIdResourceName()
                    }
                )!!
                .isEnabled()
        )
        click(text(R.string.area_app)!!)
        await(text(R.string.choose_app)!!)
        // Exercise Android's normal Back dispatch, including predictive-back compatible dialogs.
        try {
            AutoCloseInputStream(ui!!.executeShellCommand("input keyevent KEYCODE_BACK")).use {
                command ->
                command.readAllBytes()
            }
        } catch (error: IOException) {
            throw AssertionError(error)
        }
        await(Predicate { n: AccessibilityNodeInfo? -> TextUtils.equals(summary, n!!.getText()) })
        assertNull(
            find(
                Predicate { n: AccessibilityNodeInfo? ->
                    "android.widget.EditText".contentEquals(n!!.getClassName())
                }
            )
        )
    }

    private fun text(resource: Int): Predicate<AccessibilityNodeInfo?>? {
        val expected = strings!!.getString(resource)
        return Predicate { n: AccessibilityNodeInfo? -> TextUtils.equals(expected, n!!.getText()) }
    }

    private fun await(predicate: Predicate<AccessibilityNodeInfo?>): AccessibilityNodeInfo? {
        val deadline: Long = SystemClock.uptimeMillis() + 8000
        do {
            val node = find(predicate)
            if (node != null) return node
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Samsung picker did not reach the expected state")
        return null
    }

    private fun click(predicate: Predicate<AccessibilityNodeInfo?>) {
        var node = await(predicate)
        while (node != null && !node.isClickable()) node = node.getParent()
        assertNotNull(node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(200)
    }

    private fun find(predicate: Predicate<AccessibilityNodeInfo?>): AccessibilityNodeInfo? {
        val root = ui!!.getRootInActiveWindow()
        return if (root == null) null else descend(root, predicate)
    }

    private fun descend(
        node: AccessibilityNodeInfo,
        predicate: Predicate<AccessibilityNodeInfo?>,
    ): AccessibilityNodeInfo? {
        if (predicate.test(node)) return node
        for (i in 0 until node.getChildCount()) {
            val child = node.getChild(i)
            if (child == null) continue
            val found = descend(child, predicate)
            if (found != null) return found
        }
        return null
    }
}
