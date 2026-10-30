package dev.ryuya.galaxycapture

import kotlin.jvm.JvmStatic

/* Restrict the branch override to one recorder's native mix callback on the current thread. */
object SpeakerMixContext {
    private val mixer: ThreadLocal<Any?> = ThreadLocal<Any?>()

    @JvmStatic
    fun enter(owner: Any?): Any? {
        val previous = SpeakerMixContext.mixer.get()
        SpeakerMixContext.mixer.set(owner)
        return previous
    }

    @JvmStatic
    fun restore(previous: Any?) {
        if (previous == null) SpeakerMixContext.mixer.remove()
        else SpeakerMixContext.mixer.set(previous)
    }

    @JvmStatic
    fun includePlayback(owner: Any?): Boolean {
        return owner != null && owner === SpeakerMixContext.mixer.get()
    }
}
