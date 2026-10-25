package dev.ryuya.galaxycapture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selection and authorization for one recorder's playback mix, never its microphone input. */
final class CallAudioPolicy {
  static final String TAG = "dev.ryuya.galaxycapture.call_audio.v1";
  static final String PERMISSION = "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT";
  static final int VOICE = 2;

  record Selection(int uid, boolean includeCalls) {
    Selection {
      if (uid < -1) throw new IllegalArgumentException("Invalid audio target");
    }

    List<Criterion> additionalRules() {
      List<Criterion> rules = new ArrayList<>();
      if (uid >= 0) rules.add(new Criterion(4, -1, uid, false));
      if (includeCalls) rules.add(new Criterion(1, VOICE, -1, true));
      return List.copyOf(rules);
    }
  }

  record Caller(int uid, int attributedUid, String packageName, boolean packageOwned) {}

  record Criterion(int rule, int usage, int uid, boolean tagged) {}

  record Mix(
      int routeFlags,
      int role,
      boolean privilegedMedia,
      boolean callRedirection,
      List<Criterion> criteria) {}

  static boolean trustedCaller(Caller caller) {
    return caller.uid() >= 0
        && caller.uid() == caller.attributedUid()
        && caller.packageOwned()
        && ("com.android.systemui".equals(caller.packageName())
            || "com.samsung.android.app.smartcapture".equals(caller.packageName()));
  }

  static boolean authorize(
      boolean enabled,
      boolean includeCalls,
      Caller caller,
      boolean currentAudioProjection,
      boolean changesFocus,
      List<Mix> mixes) {
    if (!enabled || !includeCalls || !currentAudioProjection || changesFocus) return false;
    if (!trustedCaller(caller)) return false;
    if (mixes.size() != 1) return false;
    Mix mix = mixes.get(0);
    if (mix.routeFlags() != 3 || mix.role() != 0 || mix.privilegedMedia() || mix.callRedirection())
      return false;
    Set<Integer> usages = new HashSet<>();
    int uidRules = 0;
    for (Criterion criterion : mix.criteria()) {
      if (criterion.rule() == 4) { // RULE_MATCH_UID; exclusions are never admitted.
        if (criterion.uid() < 0 || ++uidRules > 1) return false;
      } else if (criterion.rule() == 1) { // RULE_MATCH_ATTRIBUTE_USAGE.
        if (criterion.usage() == VOICE && !criterion.tagged()) return false;
        usages.add(criterion.usage());
      } else return false;
    }
    return usages.equals(Set.of(0, 1, VOICE, 14));
  }
}
