package dev.ryuya.galaxycapture

import java.util.ArrayList
import java.util.HashSet
import java.util.List
import java.util.Set
import kotlin.jvm.JvmRecord

/* Selection and authorization for one recorder's playback mix, never its microphone input. */
object CallAudioPolicy {
    const val TAG: String = "dev.ryuya.galaxycapture.call_audio.v1"
    const val PERMISSION: String = "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT"
    const val VOICE: Int = 2

    @kotlin.jvm.JvmStatic
    fun trustedCaller(caller: CallAudioPolicy.Caller): Boolean {
        return caller.uid >= 0 &&
            caller.uid == caller.attributedUid &&
            caller.packageOwned &&
            ("com.android.systemui" == caller.packageName ||
                "com.samsung.android.app.smartcapture" == caller.packageName)
    }

    @kotlin.jvm.JvmStatic
    fun authorize(
        enabled: Boolean,
        includeCalls: Boolean,
        caller: CallAudioPolicy.Caller,
        currentAudioProjection: Boolean,
        changesFocus: Boolean,
        mixes: MutableList<CallAudioPolicy.Mix?>,
    ): Boolean {
        if (!enabled || !includeCalls || !currentAudioProjection || changesFocus) return false
        if (!CallAudioPolicy.trustedCaller(caller)) return false
        if (mixes.size != 1) return false
        val mix = mixes.get(0)
        if (
            mix!!.routeFlags != 3 ||
                mix!!.role != 0 ||
                mix!!.privilegedMedia ||
                mix!!.callRedirection
        )
            return false
        val usages = HashSet<Int?>()
        var uidRules: Int = 0
        for (criterion in mix!!.criteria!!) {
            if (criterion!!.rule == 4) { // RULE_MATCH_UID; exclusions are never admitted.
                if (criterion!!.uid < 0 || ++uidRules > 1) return false
            } else if (criterion!!.rule == 1) { // RULE_MATCH_ATTRIBUTE_USAGE.
                if (criterion!!.usage == CallAudioPolicy.VOICE && !criterion!!.tagged) return false
                usages.add(criterion!!.usage)
            } else return false
        }
        return usages == Set.of<Int?>(0, 1, CallAudioPolicy.VOICE, 14)
    }

    class Selection(uid: Int, includeCalls: Boolean) {
        val uid: Int
        val includeCalls: Boolean

        fun additionalRules(): MutableList<CallAudioPolicy.Criterion?>? {
            val rules = ArrayList<CallAudioPolicy.Criterion?>()
            if (uid >= 0) rules.add(CallAudioPolicy.Criterion(4, -1, uid, false))
            if (includeCalls)
                rules.add(CallAudioPolicy.Criterion(1, CallAudioPolicy.VOICE, -1, true))
            return List.copyOf<CallAudioPolicy.Criterion?>(rules)
        }

        init {

            require(uid >= -1) { "Invalid audio target" }

            this.uid = uid
            this.includeCalls = includeCalls
        }

        fun uid(): Int = uid

        fun includeCalls(): Boolean = includeCalls

        override fun equals(other: Any?): Boolean =
            other is Selection && uid == other.uid && includeCalls == other.includeCalls

        override fun hashCode(): Int {
            var result = uid.hashCode()
            result = 31 * result + includeCalls.hashCode()
            return result
        }

        override fun toString(): String = "Selection[uid=$uid, includeCalls=$includeCalls]"
    }

    @JvmRecord
    data class Caller(
        val uid: Int,
        val attributedUid: Int,
        val packageName: String?,
        val packageOwned: Boolean,
    ) {

        override fun toString(): String =
            "Caller[uid=$uid, attributedUid=$attributedUid, packageName=$packageName, packageOwned=$packageOwned]"
    }

    @JvmRecord
    data class Criterion(val rule: Int, val usage: Int, val uid: Int, val tagged: Boolean) {

        override fun toString(): String =
            "Criterion[rule=$rule, usage=$usage, uid=$uid, tagged=$tagged]"
    }

    @JvmRecord
    data class Mix(
        val routeFlags: Int,
        val role: Int,
        val privilegedMedia: Boolean,
        val callRedirection: Boolean,
        val criteria: MutableList<CallAudioPolicy.Criterion?>?,
    ) {

        override fun toString(): String =
            "Mix[routeFlags=$routeFlags, role=$role, privilegedMedia=$privilegedMedia, callRedirection=$callRedirection, criteria=$criteria]"
    }
}
