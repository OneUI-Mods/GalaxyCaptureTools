package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;

public class CallAudioPolicyTest {
  private static final CallAudioPolicy.Caller SAMSUNG =
      new CallAudioPolicy.Caller(10050, 10050, "com.samsung.android.app.smartcapture", true);

  private List<CallAudioPolicy.Criterion> criteria() {
    return new ArrayList<>(
        List.of(
            new CallAudioPolicy.Criterion(1, 0, -1, false),
            new CallAudioPolicy.Criterion(1, 1, -1, false),
            new CallAudioPolicy.Criterion(1, 14, -1, false),
            new CallAudioPolicy.Criterion(1, 2, -1, true)));
  }

  private CallAudioPolicy.Mix mix(List<CallAudioPolicy.Criterion> criteria) {
    return new CallAudioPolicy.Mix(3, 0, false, false, criteria);
  }

  private boolean allowed(CallAudioPolicy.Mix mix) {
    return CallAudioPolicy.authorize(true, true, SAMSUNG, true, false, List.of(mix));
  }

  @Test
  public void callSwitchDoesNotExpandTheSelectedAppOrAlterTheMediaSelection() {
    for (boolean include : new boolean[] {false, true}) {
      CallAudioPolicy.Selection selected = new CallAudioPolicy.Selection(12345, include);
      List<CallAudioPolicy.Criterion> added = selected.additionalRules();
      assertEquals(include ? 2 : 1, added.size());
      assertEquals(new CallAudioPolicy.Criterion(4, -1, 12345, false), added.get(0));
      if (include) assertEquals(new CallAudioPolicy.Criterion(1, 2, -1, true), added.get(1));
      List<CallAudioPolicy.Criterion> fullDevice =
          new CallAudioPolicy.Selection(-1, include).additionalRules();
      assertEquals(include ? 1 : 0, fullDevice.size());
      assertFalse(fullDevice.stream().anyMatch(rule -> rule.rule() == 4));
    }
  }

  @Test
  public void bothHostsNeedExplicitOptInAnOwnedAttributionAndACurrentAudioProjection() {
    for (CallAudioPolicy.Caller host :
        List.of(SAMSUNG, new CallAudioPolicy.Caller(1000, 1000, "com.android.systemui", true))) {
      assertTrue(
          CallAudioPolicy.authorize(true, true, host, true, false, List.of(mix(criteria()))));
      assertFalse(
          CallAudioPolicy.authorize(false, true, host, true, false, List.of(mix(criteria()))));
      assertFalse(
          CallAudioPolicy.authorize(true, false, host, true, false, List.of(mix(criteria()))));
      assertFalse(
          CallAudioPolicy.authorize(true, true, host, false, false, List.of(mix(criteria()))));
      assertFalse(
          CallAudioPolicy.authorize(true, true, host, true, true, List.of(mix(criteria()))));
    }
    for (CallAudioPolicy.Caller untrusted :
        List.of(
            new CallAudioPolicy.Caller(10050, 1000, SAMSUNG.packageName(), true),
            new CallAudioPolicy.Caller(10050, 10050, SAMSUNG.packageName(), false),
            new CallAudioPolicy.Caller(10050, 10050, "other.recorder", true)))
      assertFalse(
          CallAudioPolicy.authorize(true, true, untrusted, true, false, List.of(mix(criteria()))));
  }

  @Test
  public void permissionExceptionCannotEnableRedirectionRoutingOrPrivilegedMediaCapture() {
    List<CallAudioPolicy.Criterion> rules = criteria();
    for (CallAudioPolicy.Mix invalid :
        List.of(
            new CallAudioPolicy.Mix(2, 0, false, false, rules),
            new CallAudioPolicy.Mix(3, 1, false, false, rules),
            new CallAudioPolicy.Mix(3, 0, true, false, rules),
            new CallAudioPolicy.Mix(3, 0, false, true, rules))) assertFalse(allowed(invalid));
    assertFalse(CallAudioPolicy.authorize(true, true, SAMSUNG, true, false, List.of()));
    assertFalse(
        CallAudioPolicy.authorize(
            true, true, SAMSUNG, true, false, List.of(mix(rules), mix(rules))));
  }

  @Test
  public void onlyTheTaggedVoiceRuleAndAnOptionalSingleUidFilterAreAdmitted() {
    assertTrue(allowed(mix(criteria())));
    List<CallAudioPolicy.Criterion> selected = criteria();
    selected.add(new CallAudioPolicy.Criterion(4, -1, 12345, false));
    assertTrue(allowed(mix(selected)));
    for (CallAudioPolicy.Criterion unexpected :
        List.of(
            new CallAudioPolicy.Criterion(1, 6, -1, false),
            new CallAudioPolicy.Criterion(0x8004, -1, 12345, false),
            new CallAudioPolicy.Criterion(4, -1, -1, false))) {
      List<CallAudioPolicy.Criterion> rules = criteria();
      rules.add(unexpected);
      assertFalse(allowed(mix(rules)));
    }
    selected.add(new CallAudioPolicy.Criterion(4, -1, 54321, false));
    assertFalse(allowed(mix(selected)));
    List<CallAudioPolicy.Criterion> untagged = criteria();
    untagged.set(3, new CallAudioPolicy.Criterion(1, 2, -1, false));
    assertFalse(allowed(mix(untagged)));
  }

  @Test
  public void scopeIsLimitedToOnePermissionServiceAndBinderCaller() {
    Object service = new Object();
    CallAudioPermissionScope.Grant previous =
        CallAudioPermissionScope.enter(new CallAudioPermissionScope.Grant(service, 10050));
    try {
      assertTrue(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION));
      assertFalse(
          CallAudioPermissionScope.allows(
              service, 10050, "android.permission.CAPTURE_AUDIO_OUTPUT"));
      assertFalse(CallAudioPermissionScope.allows(new Object(), 10050, CallAudioPolicy.PERMISSION));
      assertFalse(CallAudioPermissionScope.allows(service, 1000, CallAudioPolicy.PERMISSION));
    } finally {
      CallAudioPermissionScope.restore(previous);
    }
    assertFalse(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION));
  }

  @Test
  public void nestedUnrelatedPolicyAndExceptionCannotInheritOrLeakAPermissionScope() {
    Object service = new Object();
    CallAudioPermissionScope.Grant previous =
        CallAudioPermissionScope.enter(new CallAudioPermissionScope.Grant(service, 10050));
    try {
      CallAudioPermissionScope.Grant outer = CallAudioPermissionScope.enter(null);
      try {
        assertFalse(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION));
        throw new IllegalStateException("Native registration failed");
      } catch (IllegalStateException expected) {
      } finally {
        CallAudioPermissionScope.restore(outer);
      }
      assertTrue(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION));
    } finally {
      CallAudioPermissionScope.restore(previous);
    }
  }

  @Test(timeout = 5000)
  public void otherBinderThreadsDoNotReceiveThePermissionException() throws Exception {
    Object service = new Object();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    CallAudioPermissionScope.Grant previous =
        CallAudioPermissionScope.enter(new CallAudioPermissionScope.Grant(service, 10050));
    try {
      assertFalse(
          worker
              .submit(
                  () -> CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION))
              .get());
    } finally {
      CallAudioPermissionScope.restore(previous);
      worker.shutdownNow();
    }
  }
}
