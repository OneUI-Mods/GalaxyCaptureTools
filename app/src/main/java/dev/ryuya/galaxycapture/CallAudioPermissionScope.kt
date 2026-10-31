package dev.ryuya.galaxycapture

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/* A permission exception belongs to one AudioService registration on one Binder thread. */
object CallAudioPermissionScope {
    private val current: ThreadLocal<CallAudioPermissionScope.Grant?> =
        ThreadLocal<CallAudioPermissionScope.Grant?>()

    @JvmStatic
    fun enter(next: CallAudioPermissionScope.Grant?): CallAudioPermissionScope.Grant? {
        val previous = CallAudioPermissionScope.current.get()
        CallAudioPermissionScope.restore(next)
        return previous
    }

    @JvmStatic
    fun restore(grant: CallAudioPermissionScope.Grant?) {
        if (grant == null) CallAudioPermissionScope.current.remove()
        else CallAudioPermissionScope.current.set(grant)
    }

    @JvmStatic
    fun allows(service: Any?, uid: Int, permission: String?): Boolean {
        val grant = CallAudioPermissionScope.current.get()
        return grant != null &&
            grant.service === service &&
            grant.uid == uid &&
            CallAudioPolicy.PERMISSION == permission
    }

    @JvmRecord
    data class Grant(val service: Any?, val uid: Int) {
        override fun toString(): String = "Grant[service=$service, uid=$uid]"
    }
}
