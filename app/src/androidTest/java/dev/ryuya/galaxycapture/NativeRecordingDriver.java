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
