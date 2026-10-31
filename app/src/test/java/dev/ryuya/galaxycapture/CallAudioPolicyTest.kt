package dev.ryuya.galaxycapture

import java.util.ArrayList
import java.util.List
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

open class CallAudioPolicyTest {
    private fun criteria(): MutableList<CallAudioPolicy.Criterion?>? {
        return ArrayList<CallAudioPolicy.Criterion?>(
            List.of<CallAudioPolicy.Criterion?>(
                CallAudioPolicy.Criterion(1, 0, -1, false),
                CallAudioPolicy.Criterion(1, 1, -1, false),
                CallAudioPolicy.Criterion(1, 14, -1, false),
                CallAudioPolicy.Criterion(1, 2, -1, true),
            )
        )
    }

    private fun mix(criteria: MutableList<CallAudioPolicy.Criterion?>?): CallAudioPolicy.Mix? {
        return CallAudioPolicy.Mix(3, 0, false, false, criteria)
    }

    private fun allowed(mix: CallAudioPolicy.Mix?): Boolean {
        return CallAudioPolicy.authorize(
            true,
            true,
            CallAudioPolicyTest.SAMSUNG,
            true,
            false,
            List.of<CallAudioPolicy.Mix?>(mix),
        )
    }

    @Test
    open fun callSwitchDoesNotExpandTheSelectedAppOrAlterTheMediaSelection() {
        for (include in booleanArrayOf(false, true)) {
            val selected = CallAudioPolicy.Selection(12345, include)
            val added = selected.additionalRules()
            assertEquals(if (include) 2 else 1, added!!.size)
            assertEquals(CallAudioPolicy.Criterion(4, -1, 12345, false), added!!.get(0))
            if (include) assertEquals(CallAudioPolicy.Criterion(1, 2, -1, true), added!!.get(1))
            val fullDevice = CallAudioPolicy.Selection(-1, include).additionalRules()
            assertEquals(if (include) 1 else 0, fullDevice!!.size)
            assertFalse(
                fullDevice!!.stream().anyMatch { rule: CallAudioPolicy.Criterion? ->
                    rule!!.rule === 4
                }
            )
        }
    }

    @Test
    open fun bothHostsNeedExplicitOptInAnOwnedAttributionAndACurrentAudioProjection() {
        for (host in
            List.of<CallAudioPolicy.Caller?>(
                CallAudioPolicyTest.SAMSUNG,
                CallAudioPolicy.Caller(1000, 1000, "com.android.systemui", true),
            )) {
            assertTrue(
                CallAudioPolicy.authorize(
                    true,
                    true,
                    host,
                    true,
                    false,
                    List.of<CallAudioPolicy.Mix?>(mix(criteria())),
                )
            )
            assertFalse(
                CallAudioPolicy.authorize(
                    false,
                    true,
                    host,
                    true,
                    false,
                    List.of<CallAudioPolicy.Mix?>(mix(criteria())),
                )
            )
            assertFalse(
                CallAudioPolicy.authorize(
                    true,
                    false,
                    host,
                    true,
                    false,
                    List.of<CallAudioPolicy.Mix?>(mix(criteria())),
                )
            )
            assertFalse(
                CallAudioPolicy.authorize(
                    true,
                    true,
                    host,
                    false,
                    false,
                    List.of<CallAudioPolicy.Mix?>(mix(criteria())),
                )
            )
            assertFalse(
                CallAudioPolicy.authorize(
                    true,
                    true,
                    host,
                    true,
                    true,
                    List.of<CallAudioPolicy.Mix?>(mix(criteria())),
                )
            )
        }
        for (untrusted in
            List.of<CallAudioPolicy.Caller?>(
                CallAudioPolicy.Caller(
                    10050,
                    1000,
                    CallAudioPolicyTest.SAMSUNG.packageName,
                    true,
                ),
                CallAudioPolicy.Caller(
                    10050,
                    10050,
                    CallAudioPolicyTest.SAMSUNG.packageName,
                    false,
                ),
                CallAudioPolicy.Caller(10050, 10050, "other.recorder", true),
            )) assertFalse(
            CallAudioPolicy.authorize(
                true,
                true,
                untrusted,
                true,
                false,
                List.of<CallAudioPolicy.Mix?>(mix(criteria())),
            )
        )
    }

    @Test
    open fun permissionExceptionCannotEnableRedirectionRoutingOrPrivilegedMediaCapture() {
        val rules = criteria()
        for (invalid in
            List.of<CallAudioPolicy.Mix?>(
                CallAudioPolicy.Mix(2, 0, false, false, rules),
                CallAudioPolicy.Mix(3, 1, false, false, rules),
                CallAudioPolicy.Mix(3, 0, true, false, rules),
                CallAudioPolicy.Mix(3, 0, false, true, rules),
            )) assertFalse(allowed(invalid))
        assertFalse(
            CallAudioPolicy.authorize(
                true,
                true,
                CallAudioPolicyTest.SAMSUNG,
                true,
                false,
                java.util.List.of<CallAudioPolicy.Mix?>(),
            )
        )
        assertFalse(
            CallAudioPolicy.authorize(
                true,
                true,
                CallAudioPolicyTest.SAMSUNG,
                true,
                false,
                List.of<CallAudioPolicy.Mix?>(mix(rules), mix(rules)),
            )
        )
    }

    @Test
    open fun onlyTheTaggedVoiceRuleAndAnOptionalSingleUidFilterAreAdmitted() {
        assertTrue(allowed(mix(criteria())))
        val selected = criteria()
        selected!!.add(CallAudioPolicy.Criterion(4, -1, 12345, false))
        assertTrue(allowed(mix(selected)))
        for (unexpected in
            List.of<CallAudioPolicy.Criterion?>(
                CallAudioPolicy.Criterion(1, 6, -1, false),
                CallAudioPolicy.Criterion(0x8004, -1, 12345, false),
                CallAudioPolicy.Criterion(4, -1, -1, false),
            )) {
            val rules = criteria()
            rules!!.add(unexpected)
            assertFalse(allowed(mix(rules)))
        }
        selected!!.add(CallAudioPolicy.Criterion(4, -1, 54321, false))
        assertFalse(allowed(mix(selected)))
        val untagged = criteria()
        untagged!!.set(3, CallAudioPolicy.Criterion(1, 2, -1, false))
        assertFalse(allowed(mix(untagged)))
    }

    @Test
    open fun scopeIsLimitedToOnePermissionServiceAndBinderCaller() {
        val service = Any()
        val previous =
            CallAudioPermissionScope.enter(CallAudioPermissionScope.Grant(service, 10050))
        try {
            assertTrue(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION))
            assertFalse(
                CallAudioPermissionScope.allows(
                    service,
                    10050,
                    "android.permission.CAPTURE_AUDIO_OUTPUT",
                )
            )
            assertFalse(CallAudioPermissionScope.allows(Any(), 10050, CallAudioPolicy.PERMISSION))
            assertFalse(CallAudioPermissionScope.allows(service, 1000, CallAudioPolicy.PERMISSION))
        } finally {
            CallAudioPermissionScope.restore(previous)
        }
        assertFalse(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION))
    }

    @Test
    open fun nestedUnrelatedPolicyAndExceptionCannotInheritOrLeakAPermissionScope() {
        val service = Any()
        val previous =
            CallAudioPermissionScope.enter(CallAudioPermissionScope.Grant(service, 10050))
        try {
            val outer = CallAudioPermissionScope.enter(null)
            try {
                assertFalse(
                    CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION)
                )
                throw IllegalStateException("Native registration failed")
            } catch (expected: IllegalStateException) {} finally {
                CallAudioPermissionScope.restore(outer)
            }
            assertTrue(CallAudioPermissionScope.allows(service, 10050, CallAudioPolicy.PERMISSION))
        } finally {
            CallAudioPermissionScope.restore(previous)
        }
    }

    @Test(timeout = 5000)
    @Throws(Exception::class)
    open fun otherBinderThreadsDoNotReceiveThePermissionException() {
        val service = Any()
        val worker = Executors.newSingleThreadExecutor()
        val previous =
            CallAudioPermissionScope.enter(CallAudioPermissionScope.Grant(service, 10050))
        try {
            assertFalse(
                worker
                    .submit<Boolean?>(
                        Callable {
                            CallAudioPermissionScope.allows(
                                service,
                                10050,
                                CallAudioPolicy.PERMISSION,
                            )
                        }
                    )
                    .get()
            )
        } finally {
            CallAudioPermissionScope.restore(previous)
            worker.shutdownNow()
        }
    }

    companion object {
        private val SAMSUNG: CallAudioPolicy.Caller =
            CallAudioPolicy.Caller(10050, 10050, "com.samsung.android.app.smartcapture", true)
    }
}
