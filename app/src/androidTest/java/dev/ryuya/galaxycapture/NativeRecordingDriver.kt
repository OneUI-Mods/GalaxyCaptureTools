package dev.ryuya.galaxycapture

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/* Exact text entry for the native dialog, independent of the user's Japanese IME mode. */
open class NativeRecordingDriver {
    @Test
    open fun enterTargetQuery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = captureRoot()
        assertNotNull(root)
        assertEquals("com.samsung.android.app.smartcapture", root!!.getPackageName().toString())
        val edit = findEdit(root!!)
        assertNotNull(edit)
        val action = Bundle()
        action.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            InstrumentationRegistry.getArguments().getString("query", "Capture"),
        )
        assertTrue(edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, action))
    }

    @Test
    open fun inspectCaptureControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = captureRoot()
        assertNotNull(root)
        val pkg = root!!.getPackageName().toString()
        assertTrue(
            pkg == "com.android.systemui" ||
                pkg == "com.samsung.android.app.smartcapture" ||
                pkg == "dev.ryuya.galaxycapture"
        )
        val label = InstrumentationRegistry.getArguments().getString("label", "")
        val lines = StringBuilder("Package: " + pkg + "\n")
        describe(root!!, lines)
        val result = Bundle()
        result.putString("ui", lines.toString())
        instrumentation.sendStatus(0, result)
        if (!label.isEmpty()) {
            val matches = root!!.findAccessibilityNodeInfosByText(label)
            var target: AccessibilityNodeInfo? = null
            for (node in matches) if (
                (node.getText() != null && node.getText().toString() == label) ||
                    (node.getContentDescription() != null &&
                        node.getContentDescription().toString() == label)
            ) {
                target = node
                break
            }
            assertNotNull("Missing control: " + label, target)
            while (target != null && !target.isClickable()) target = target.getParent()
            assertNotNull("No clickable parent: " + label, target)
            assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }

    @Test
    open fun setVisibleRecordingLevel() {
        val root = captureRoot()
        assertNotNull(root)
        assertEquals("com.samsung.android.app.smartcapture", root!!.getPackageName().toString())
        val label = InstrumentationRegistry.getArguments().getString("label")
        val nodes = root!!.findAccessibilityNodeInfosByText(label)
        var row: AccessibilityNodeInfo? = null
        for (node in nodes) if (label == node.getText().toString()) {
            row = node
            break
        }
        assertNotNull(row)
        var slider: AccessibilityNodeInfo? = null
        while (row != null && slider == null) {
            slider = findRange(row)
            row = row.getParent()
        }
        assertNotNull(slider)
        val value = Bundle()
        value.putFloat(
            AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
            InstrumentationRegistry.getArguments().getString("value")!!.toFloat(),
        )
        assertTrue(
            slider!!.performAction(
                AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                value,
            )
        )
    }

    private fun findRange(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.getRangeInfo() != null && node.isEnabled()) return node
        for (i in 0 until node.getChildCount()) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findRange(child)
                if (result != null) return result
            }
        }
        return null
    }

    @Test
    open fun restoreCaptureControlDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
        assertTrue(
            context
                .getSharedPreferences("config", 0)
                .edit()
                .putBoolean("countdown", true)
                .putBoolean("hide_recording_controls", false)
                .putBoolean("exclude_capture_controls", true)
                .commit()
        )
        Startup.notifyHosts(context)
    }

    private fun captureRoot(): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation()
        val info = automation.getServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.setServiceInfo(info)
        var active: AccessibilityNodeInfo? = null
        for (attempt in 0..29) {
            for (window in automation.getWindows()) {
                val root = window.getRoot()
                if (
                    root != null &&
                        "com.samsung.android.app.smartcapture" == root.getPackageName().toString()
                )
                    return root
            }
            active = automation.getRootInActiveWindow()
            if (
                active != null &&
                    ("com.android.systemui" == active.getPackageName().toString() ||
                        "dev.ryuya.galaxycapture" == active.getPackageName().toString())
            )
                return active
            SystemClock.sleep(100)
        }
        return active
    }

    private fun describe(node: AccessibilityNodeInfo, lines: StringBuilder) {
        var text = node.getText()
        if (text == null) text = node.getContentDescription()
        // Avoid collecting unrelated notification contents while inspecting the native stop
        // control.
        if (
            text != null &&
                (node.getPackageName().toString() == "dev.ryuya.galaxycapture" ||
                    node.getPackageName().toString() == "com.samsung.android.app.smartcapture" ||
                    text.toString().contains("録画") ||
                    text.toString().contains("停止"))
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            lines
                .append(text)
                .append(" ")
                .append(bounds)
                .append(" checked=")
                .append(node.isChecked())
                .append(" enabled=")
                .append(node.isEnabled())
                .append("\n")
        }
        for (i in 0 until node.getChildCount()) {
            val child = node.getChild(i)
            if (child != null) describe(child, lines)
        }
    }

    private fun findEdit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node
        for (i in 0 until node.getChildCount()) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findEdit(child)
                if (result != null) return result
            }
        }
        return null
    }
}
