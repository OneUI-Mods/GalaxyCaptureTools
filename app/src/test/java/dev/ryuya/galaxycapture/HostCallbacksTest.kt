package dev.ryuya.galaxycapture

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import org.junit.Assert.*
import org.junit.Test

open class HostCallbacksTest {
    fun interface Click {
        fun b(preference: Any?): Boolean
    }

    fun interface Change {
        fun onPreferenceChange(preference: Any?, value: Any?): Boolean
    }

    fun interface Switch {
        fun a(widget: Any?, checked: Boolean)
    }

    @Test
    open fun obfuscatedBooleanClickDoesNotReturnObjectMethodText() {
        val clicks = intArrayOf(0)
        val callback: HostCallbacksTest.Click? =
            HostCallbacks.create(
                javaClass.getClassLoader(),
                HostCallbacksTest.Click::class.java,
                InvocationHandler { p: Any?, m: Method?, args: Array<Any?>? ->
                    clicks[0]++
                    true
                },
            ) as HostCallbacksTest.Click?
        assertTrue(callback!!.b(Any()))
        assertEquals(1, clicks[0])
        assertTrue(callback == callback)
        assertFalse(callback == Any())
        assertEquals(System.identityHashCode(callback), callback.hashCode())
        assertNotNull(callback.toString())
        assertEquals(1, clicks[0])
    }

    @Test
    open fun nativeSwitchAndPreferenceValuesRetainTheirTypes() {
        val changed = booleanArrayOf(false)
        val toggle: HostCallbacksTest.Switch? =
            HostCallbacks.create(
                javaClass.getClassLoader(),
                HostCallbacksTest.Switch::class.java,
                InvocationHandler { p: Any?, m: Method?, args: Array<Any?>? ->
                    changed[0] = args!![1] as Boolean
                    null
                },
            ) as HostCallbacksTest.Switch?
        toggle!!.a(Any(), true)
        assertTrue(changed[0])
        val level: HostCallbacksTest.Change? =
            HostCallbacks.create(
                javaClass.getClassLoader(),
                HostCallbacksTest.Change::class.java,
                InvocationHandler { p: Any?, m: Method?, args: Array<Any?>? ->
                    args!![1] as Int == 25
                },
            ) as HostCallbacksTest.Change?
        assertTrue(level!!.onPreferenceChange(Any(), 25))
        assertFalse(level!!.onPreferenceChange(Any(), 30))
    }

    @Test
    open fun failedUiCallbackCannotCrashTheHostOrAcceptTheChange() {
        val callback: HostCallbacksTest.Click? =
            HostCallbacks.create(
                javaClass.getClassLoader(),
                HostCallbacksTest.Click::class.java,
                InvocationHandler { p: Any?, m: Method?, args: Array<Any?>? ->
                    throw IllegalStateException("Test callback failure")
                },
            ) as HostCallbacksTest.Click?
        assertFalse(callback!!.b(Any()))
    }
}
