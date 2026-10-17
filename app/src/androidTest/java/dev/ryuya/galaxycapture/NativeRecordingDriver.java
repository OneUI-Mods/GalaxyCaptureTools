package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Exact text entry for the native dialog, independent of the user's Japanese IME mode. */
public class NativeRecordingDriver {
  @Test
  public void enterTargetQuery() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var root = instrumentation.getUiAutomation().getRootInActiveWindow();
    assertNotNull(root);
    assertEquals("com.samsung.android.app.smartcapture", root.getPackageName().toString());
    var edit = findEdit(root);
    assertNotNull(edit);
    Bundle action = new Bundle();
    action.putCharSequence(
        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
        InstrumentationRegistry.getArguments().getString("query", "Capture"));
    assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, action));
  }

  @Test
  public void inspectCaptureControls() {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var root = instrumentation.getUiAutomation().getRootInActiveWindow();
    assertNotNull(root);
    String pkg = root.getPackageName().toString();
    assertTrue(pkg.equals("com.android.systemui")
        || pkg.equals("com.samsung.android.app.smartcapture")
        || pkg.equals("dev.ryuya.galaxycapture"));
    String label = InstrumentationRegistry.getArguments().getString("label", "");
    StringBuilder lines = new StringBuilder("Package: " + pkg + "\n");
    describe(root, lines);
    Bundle result = new Bundle();
    result.putString("ui", lines.toString());
    instrumentation.sendStatus(0, result);
    if (!label.isEmpty()) {
      var matches = root.findAccessibilityNodeInfosByText(label);
      AccessibilityNodeInfo target = null;
      for (var node : matches)
        if (node.getText() != null && node.getText().toString().equals(label)) {
          target = node;
          break;
        }
      assertNotNull("Missing control: " + label, target);
      while (target != null && !target.isClickable()) target = target.getParent();
      assertNotNull("No clickable parent: " + label, target);
      assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
  }

  @Test
  public void restoreCaptureControlDefaults() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(context.getSharedPreferences("config", 0).edit()
        .putBoolean("countdown", true).putBoolean("hide_recording_controls", false)
        .putBoolean("exclude_capture_controls", true).commit());
    Startup.notifyHosts(context);
  }

  private void describe(AccessibilityNodeInfo node, StringBuilder lines) {
    CharSequence text = node.getText();
    if (text == null) text = node.getContentDescription();
    // Avoid collecting unrelated notification contents while inspecting the native stop control.
    if (text != null && (node.getPackageName().toString().equals("dev.ryuya.galaxycapture")
        || node.getPackageName().toString().equals("com.samsung.android.app.smartcapture")
        || text.toString().contains("録画") || text.toString().contains("停止"))) {
      android.graphics.Rect bounds = new android.graphics.Rect();
      node.getBoundsInScreen(bounds);
      lines.append(text).append(" ").append(bounds).append(" checked=")
          .append(node.isChecked()).append(" enabled=").append(node.isEnabled()).append("\n");
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      var child = node.getChild(i);
      if (child != null) describe(child, lines);
    }
  }

  private AccessibilityNodeInfo findEdit(AccessibilityNodeInfo node) {
    if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      var child = node.getChild(i);
      if (child != null) {
        var result = findEdit(child);
        if (result != null) return result;
      }
    }
    return null;
  }
}
