package dev.ryuya.galaxycapture;
import static org.junit.Assert.*;
import org.junit.Test;

public class HostCallbacksTest {
  interface Click { boolean b(Object preference); }
  interface Change { boolean onPreferenceChange(Object preference, Object value); }
  interface Switch { void a(Object widget, boolean checked); }
  @Test public void obfuscatedBooleanClickDoesNotReturnObjectMethodText() {
    int[] clicks = {0};
    Click callback = (Click) HostCallbacks.create(getClass().getClassLoader(), Click.class,
        (p, m, args) -> { clicks[0]++; return true; });
    assertTrue(callback.b(new Object()));
    assertEquals(1, clicks[0]);
    assertTrue(callback.equals(callback));
    assertFalse(callback.equals(new Object()));
    assertEquals(System.identityHashCode(callback), callback.hashCode());
    assertNotNull(callback.toString());
    assertEquals(1, clicks[0]);
  }
  @Test public void nativeSwitchAndPreferenceValuesRetainTheirTypes() {
    boolean[] changed = {false};
    Switch toggle = (Switch) HostCallbacks.create(getClass().getClassLoader(), Switch.class,
        (p, m, args) -> { changed[0] = (boolean) args[1]; return null; });
    toggle.a(new Object(), true); assertTrue(changed[0]);
    Change level = (Change) HostCallbacks.create(getClass().getClassLoader(), Change.class,
        (p, m, args) -> (int) args[1] == 25);
    assertTrue(level.onPreferenceChange(new Object(), 25));
    assertFalse(level.onPreferenceChange(new Object(), 30));
  }
  @Test public void failedUiCallbackCannotCrashTheHostOrAcceptTheChange() {
    Click callback = (Click) HostCallbacks.create(getClass().getClassLoader(), Click.class,
        (p, m, args) -> { throw new IllegalStateException("Test callback failure"); });
    assertFalse(callback.b(new Object()));
  }
}
