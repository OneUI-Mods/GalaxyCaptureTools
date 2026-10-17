package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class SpeakerMixContextTest {
  @Test public void originalBehaviorOutsideMixCallback() {
    assertFalse(SpeakerMixContext.includePlayback(new Object()));
    assertFalse(SpeakerMixContext.includePlayback(null));
  }
  @Test public void overrideBelongsOnlyToTheCurrentRecorderAndEndsOnException() {
    Object owner = new Object();
    Object previous = SpeakerMixContext.enter(owner);
    try {
      assertTrue(SpeakerMixContext.includePlayback(owner));
      assertFalse(SpeakerMixContext.includePlayback(new Object()));
      throw new IllegalStateException("simulated native mixing failure");
    } catch (IllegalStateException expected) {
    } finally { SpeakerMixContext.restore(previous); }
    assertFalse(SpeakerMixContext.includePlayback(owner));
  }
  @Test public void anotherThreadKeepsRealHeadphoneState() throws Exception {
    Object owner = new Object();
    Object previous = SpeakerMixContext.enter(owner);
    try {
      AtomicBoolean overridden = new AtomicBoolean(true);
      Thread route = new Thread(() -> overridden.set(SpeakerMixContext.includePlayback(owner)));
      route.start(); route.join();
      assertFalse(overridden.get());
      assertTrue(SpeakerMixContext.includePlayback(owner));
    } finally { SpeakerMixContext.restore(previous); }
  }
  @Test public void nestedCallbacksRestoreTheirOwner() {
    Object first = new Object(), second = new Object();
    Object outer = SpeakerMixContext.enter(first);
    try {
      Object inner = SpeakerMixContext.enter(second);
      try {
        assertFalse(SpeakerMixContext.includePlayback(first));
        assertTrue(SpeakerMixContext.includePlayback(second));
      } finally { SpeakerMixContext.restore(inner); }
      assertTrue(SpeakerMixContext.includePlayback(first));
    } finally { SpeakerMixContext.restore(outer); }
    assertFalse(SpeakerMixContext.includePlayback(first));
  }
}
