package dev.ryuya.galaxycapture;

import java.util.Properties;

/** Pure restart accounting, shared with the durable host guard. */
final class RestartLedger {
  static void observe(Properties state, long now, int pid) {
    boolean interrupted =
        Boolean.parseBoolean(state.getProperty("active", "false"))
            && !state.getProperty("pid", "").equals(Integer.toString(pid));
    if (interrupted) {
      long last = Long.parseLong(state.getProperty("time", "0"));
      int count =
          now >= last && now - last < 120000
              ? Integer.parseInt(state.getProperty("count", "0")) + 1
              : 1;
      state.setProperty("count", Integer.toString(count));
      if (count >= 3) state.setProperty("blocked", "true");
    }
    state.setProperty("active", "false");
    state.setProperty("pid", Integer.toString(pid));
  }
}
