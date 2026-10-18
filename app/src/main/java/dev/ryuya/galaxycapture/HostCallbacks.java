package dev.ryuya.galaxycapture;

import dev.ryuya.galaxycapture.hook.Hooks;
import java.lang.reflect.*;

/** Host callback method names may be obfuscated; Object methods are the only name dispatch. */
final class HostCallbacks {
  static Object create(ClassLoader loader, Class<?> type, InvocationHandler handler) {
    return Proxy.newProxyInstance(
        loader,
        new Class<?>[] {type},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == args[0];
              default -> "GalaxyCapture settings listener";
            };
          }
          try {
            return handler.invoke(proxy, method, args);
          } catch (Throwable error) {
            Hooks.log("GalaxyCapture settings callback: " + error);
            return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
          }
        });
  }
}
