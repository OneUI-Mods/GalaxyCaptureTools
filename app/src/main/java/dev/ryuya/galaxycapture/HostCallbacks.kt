package dev.ryuya.galaxycapture

import dev.ryuya.galaxycapture.hook.Hooks
import java.lang.Boolean as JavaBoolean
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.jvm.JvmStatic
import kotlin.math.log

/* Host callback method names may be obfuscated; Object methods are the only name dispatch. */
object HostCallbacks {
    @JvmStatic
    fun create(loader: ClassLoader?, type: Class<*>?, handler: InvocationHandler): Any? {
        return Proxy.newProxyInstance(
            loader,
            arrayOf<Class<*>?>(type),
            InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                if (method!!.getDeclaringClass() == Any::class.java) {
                    return@InvocationHandler when (method!!.getName()) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args!![0]
                        else -> "GalaxyCapture settings listener"
                    }
                }
                try {
                    return@InvocationHandler handler.invoke(proxy, method, args)
                } catch (error: Throwable) {
                    Hooks.log("GalaxyCapture settings callback: " + error)
                    return@InvocationHandler if (
                        method!!.getReturnType() == Boolean::class.javaPrimitiveType
                    )
                        JavaBoolean.FALSE
                    else null
                }
            },
        )
    }
}
