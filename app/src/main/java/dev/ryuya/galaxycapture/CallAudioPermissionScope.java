package dev.ryuya.galaxycapture;

/** A permission exception belongs to one AudioService registration on one Binder thread. */
final class CallAudioPermissionScope {
  record Grant(Object service, int uid) {}

  private static final ThreadLocal<Grant> current = new ThreadLocal<>();

  static Grant enter(Grant next) {
    Grant previous = current.get();
    restore(next);
    return previous;
  }

  static void restore(Grant grant) {
    if (grant == null) current.remove();
    else current.set(grant);
  }

  static boolean allows(Object service, int uid, String permission) {
    Grant grant = current.get();
    return grant != null
        && grant.service() == service
        && grant.uid() == uid
        && CallAudioPolicy.PERMISSION.equals(permission);
  }
}
