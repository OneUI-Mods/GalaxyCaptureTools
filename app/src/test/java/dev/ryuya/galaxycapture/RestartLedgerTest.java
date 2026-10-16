package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import java.util.Properties;
import org.junit.Test;

public class RestartLedgerTest {
  @Test
  public void threeInterruptedRecordingsStopFutureHooks() {
    var p = new Properties();
    for (int i = 1; i <= 3; i++) {
      arm(p, i, 1000 * i);
      RestartLedger.observe(p, 1000 * i + 10, i + 1);
      assertEquals(i >= 3, Boolean.parseBoolean(p.getProperty("blocked", "false")));
    }
    assertEquals("3", p.getProperty("count"));
  }

  @Test
  public void completedRecordingDoesNotCountAProcessRestart() {
    var p = new Properties();
    arm(p, 1, 1000);
    p.setProperty("active", "false");
    RestartLedger.observe(p, 1010, 2);
    assertEquals("0", p.getProperty("count", "0"));
  }

  @Test
  public void sameProcessCannotBeCountedTwice() {
    var p = new Properties();
    arm(p, 1, 1000);
    RestartLedger.observe(p, 1010, 1);
    assertEquals("0", p.getProperty("count", "0"));
  }

  @Test
  public void olderFailuresDoNotMakeAnUnrelatedRestartLookLikeALoop() {
    var p = new Properties();
    p.setProperty("count", "2");
    arm(p, 1, 1000);
    RestartLedger.observe(p, 121000, 2);
    assertEquals("1", p.getProperty("count"));
    assertFalse(Boolean.parseBoolean(p.getProperty("blocked", "false")));
  }

  @Test
  public void clockRollbackDoesNotCompleteAFalseCrashLoop() {
    var p = new Properties();
    p.setProperty("count", "2");
    arm(p, 1, 1000);
    RestartLedger.observe(p, 900, 2);
    assertEquals("1", p.getProperty("count"));
    assertFalse(Boolean.parseBoolean(p.getProperty("blocked", "false")));
  }

  @Test
  public void anExistingStopSurvivesNormalRestartsAndTimePassing() {
    var p = new Properties();
    p.setProperty("blocked", "true");
    RestartLedger.observe(p, 999999, 3);
    assertTrue(Boolean.parseBoolean(p.getProperty("blocked")));
    RestartLedger.observe(p, 1999999, 4);
    assertTrue(Boolean.parseBoolean(p.getProperty("blocked")));
  }

  @Test
  public void observingARecoveredProcessDoesNotKeepItArmed() {
    var p = new Properties();
    arm(p, 1, 1000);
    RestartLedger.observe(p, 1010, 2);
    RestartLedger.observe(p, 1020, 3);
    assertEquals("1", p.getProperty("count"));
  }

  private static void arm(Properties p, int pid, long now) {
    p.setProperty("active", "true");
    p.setProperty("pid", Integer.toString(pid));
    p.setProperty("time", Long.toString(now));
  }
}
