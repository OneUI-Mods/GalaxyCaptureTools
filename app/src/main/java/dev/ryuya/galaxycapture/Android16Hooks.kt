package dev.ryuya.galaxycapture

import kotlin.jvm.JvmStatic

/* Frozen Android 16 admission rules; hook sources are recorded in docs/compatibility. */
object Android16Hooks {
    const val DIALOG_BUILDER: String = "M4.c"
    const val DIALOG_CREATE: String = "f"
    const val BUILD: String = "BP4A.251205.006.S948QOPS1AZHL"

    @JvmStatic
    fun supports(
        sdk: Int,
        manufacturer: String?,
        model: String?,
        device: String?,
        display: String?,
    ): Boolean {
        return sdk == 36 &&
            "samsung".equals(manufacturer, ignoreCase = true) &&
            Android16Hooks.BUILD == display
    }
}
