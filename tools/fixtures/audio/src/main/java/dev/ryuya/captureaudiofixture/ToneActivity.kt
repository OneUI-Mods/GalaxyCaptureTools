package dev.ryuya.captureaudiofixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView

/* Separate UID, used only to detect unwanted audio in an app-scoped recording. */
open class ToneActivity : Activity() {
    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startForegroundService(
            Intent(this, ToneService::class.java)
                .putExtra("voice", getIntent().getBooleanExtra("voice", false))
        )
        val text = TextView(this)
        text.setText("INTERNAL TEST: other UID, media 880 Hz / optional voice 1100 Hz\nTap to stop")
        text.setTextSize(26f)
        text.setOnClickListener(
            OnClickListener { v: View? ->
                stopService(Intent(this, ToneService::class.java))
                finish()
            }
        )
        setContentView(text)
    }
}
