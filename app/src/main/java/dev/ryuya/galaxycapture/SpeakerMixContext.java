package dev.ryuya.galaxycapture;

/** Restrict the branch override to one recorder's native mix callback on the current thread. */
final class SpeakerMixContext {
  private static final ThreadLocal<Object> mixer = new ThreadLocal<>();
  static Object enter(Object owner) {
    Object previous = mixer.get();
    mixer.set(owner);
    return previous;
  }
  static void restore(Object previous) {
    if (previous == null) mixer.remove(); else mixer.set(previous);
  }
  static boolean includePlayback(Object owner) {
    return owner != null && owner == mixer.get();
  }
}
