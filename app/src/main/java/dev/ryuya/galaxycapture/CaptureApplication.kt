package dev.ryuya.galaxycapture

import android.app.Application
import com.google.android.material.color.DynamicColors

class CaptureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
