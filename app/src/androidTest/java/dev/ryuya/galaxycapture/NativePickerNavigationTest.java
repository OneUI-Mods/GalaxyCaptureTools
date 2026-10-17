package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.function.Predicate;
import org.junit.Test;

/** Exercises the Samsung dialog itself, including cancelling after a confirmed target. */
public class NativePickerNavigationTest {
  private final UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
  private final android.content.Context strings =
      InstrumentationRegistry.getInstrumentation().getTargetContext();

  @Test
  public void separatePickerSupportsRecentsScrollingSearchAndBackWithoutLosingTarget() {
    assertNull(find(n -> "android.widget.EditText".contentEquals(n.getClassName())));
    click(text(R.string.area_app));
    await(text(R.string.choose_app));
    await(n -> "android.widget.ListView".contentEquals(n.getClassName()) && n.getChildCount() > 0);
    assertNotNull(find(text(R.string.recent_apps)));
    var list = find(n -> "android.widget.ListView".contentEquals(n.getClassName()));
    assertTrue("Recent apps should scroll", list.isScrollable());
    assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
    click(text(R.string.other_apps));
    await(text(R.string.all_apps));
    var edit = await(n -> "android.widget.EditText".contentEquals(n.getClassName()));
    Bundle query = new Bundle();
    query.putCharSequence(
        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "dev.ryuya.galaxycapture");
    assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, query));
    await(text(R.string.search_results));
    String label = strings.getString(R.string.app_name);
    click(n -> android.text.TextUtils.equals(label, n.getText()));
    String summary = strings.getString(R.string.selected_target, label) + "  ›";
    await(n -> android.text.TextUtils.equals(summary, n.getText()));
    assertNull(find(n -> "android.widget.EditText".contentEquals(n.getClassName())));
    assertTrue(find(n -> "android:id/button1".equals(n.getViewIdResourceName())).isEnabled());
    click(n -> android.text.TextUtils.equals(summary, n.getText()));
    await(text(R.string.choose_app));
    click(text(R.string.back_to_recording));
    await(n -> android.text.TextUtils.equals(summary, n.getText()));
    assertTrue(find(n -> "android:id/button1".equals(n.getViewIdResourceName())).isEnabled());
    click(text(R.string.area_app));
    await(text(R.string.choose_app));
    // Exercise Android's normal Back dispatch, including predictive-back compatible dialogs.
    try (var command =
        new android.os.ParcelFileDescriptor.AutoCloseInputStream(
            ui.executeShellCommand("input keyevent KEYCODE_BACK"))) {
      command.readAllBytes();
    } catch (java.io.IOException error) {
      throw new AssertionError(error);
    }
    await(n -> android.text.TextUtils.equals(summary, n.getText()));
    assertNull(find(n -> "android.widget.EditText".contentEquals(n.getClassName())));
  }

  private Predicate<AccessibilityNodeInfo> text(int resource) {
    String expected = strings.getString(resource);
    return n -> android.text.TextUtils.equals(expected, n.getText());
  }

  private AccessibilityNodeInfo await(Predicate<AccessibilityNodeInfo> predicate) {
    long deadline = SystemClock.uptimeMillis() + 8000;
    do {
      var node = find(predicate);
      if (node != null) return node;
      SystemClock.sleep(100);
    } while (SystemClock.uptimeMillis() < deadline);
    fail("Samsung picker did not reach the expected state");
    return null;
  }

  private void click(Predicate<AccessibilityNodeInfo> predicate) {
    var node = await(predicate);
    while (node != null && !node.isClickable()) node = node.getParent();
    assertNotNull(node);
    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    SystemClock.sleep(200);
  }

  private AccessibilityNodeInfo find(Predicate<AccessibilityNodeInfo> predicate) {
    var root = ui.getRootInActiveWindow();
    return root == null ? null : descend(root, predicate);
  }

  private AccessibilityNodeInfo descend(
      AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
    if (predicate.test(node)) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      var child = node.getChild(i);
      if (child == null) continue;
      var found = descend(child, predicate);
      if (found != null) return found;
    }
    return null;
  }
}
