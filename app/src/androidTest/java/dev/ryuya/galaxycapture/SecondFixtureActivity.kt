package dev.ryuya.galaxycapture

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView

open class SecondFixtureActivity : Activity() {
    public override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val v = TextView(this)
        v.setText("WINDOW B — MUST NOT APPEAR IN WINDOW-LOCKED RECORDING")
        v.setTextColor(Color.WHITE)
        v.setTextSize(36f)
        v.setBackgroundColor(Color.RED)
        setContentView(v)
    }
}
