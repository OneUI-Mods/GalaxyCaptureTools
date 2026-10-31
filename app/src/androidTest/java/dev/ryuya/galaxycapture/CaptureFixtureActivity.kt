package dev.ryuya.galaxycapture

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.os.Bundle
import android.view.*
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams
import dev.ryuya.fixtureaudio.TonePlayback
import kotlin.jvm.JvmField
import kotlin.math.min

/* Internal test APK only. A new Activity must never leak into a window-locked recording. */
open class CaptureFixtureActivity : Activity() {
    private var tone: TonePlayback? = null
    private var voiceTone: TonePlayback? = null

    public override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b != null) getIntent().putExtra("rotation", b.getInt("rotation", 0))
        apply(getIntent())
        updateAudio(getIntent())
    }

    override fun onDestroy() {
        if (tone != null) {
            tone!!.close()
            tone = null
        }
        if (voiceTone != null) {
            voiceTone!!.close()
            voiceTone = null
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("rotation", getIntent().getIntExtra("rotation", 0))
        super.onSaveInstanceState(out)
    }

    public override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        for (key in arrayOf<String>("tone", "voice")) if (!i.hasExtra(key))
            i.putExtra(key, getIntent().getBooleanExtra(key, false))
        setIntent(i)
        apply(i)
        updateAudio(i)
    }

    private fun updateAudio(intent: Intent) {
        if (intent.getBooleanExtra("tone", false)) {
            if (tone == null) tone = TonePlayback(AudioAttributes.USAGE_MEDIA, 440)
        } else if (tone != null) {
            tone!!.close()
            tone = null
        }
        if (intent.getBooleanExtra("voice", false)) {
            if (voiceTone == null)
                voiceTone = TonePlayback(AudioAttributes.USAGE_VOICE_COMMUNICATION, 660)
        } else if (voiceTone != null) {
            voiceTone!!.close()
            voiceTone = null
        }
    }

    private fun apply(i: Intent) {
        getWindow().addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (i.getBooleanExtra("secure", false)) getWindow().addFlags(LayoutParams.FLAG_SECURE)
        else getWindow().clearFlags(LayoutParams.FLAG_SECURE)
        if (i.hasExtra("rotation"))
            setRequestedOrientation(
                when (i.getIntExtra("rotation", 0)) {
                    1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            )
        setContentView(
            object : View(this) {
                @JvmField val p: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

                override fun onDraw(c: Canvas) {
                    val w = getWidth().toFloat()
                    val h = getHeight().toFloat()
                    val colors = intArrayOf(-0x1b31bf, -0xd67f21, -0xd94089, -0x4ab82b)
                    for (n in 0..3) {
                        val x: Float = (n % 2) * w / 2
                        val y: Float = (n / 2) * h / 2
                        p.setColor(colors[n])
                        c.drawRect(x, y, x + w / 2, y + h / 2, p)
                        p.setColor(Color.WHITE)
                        p.setTextSize(min(w, h) / 9)
                        c.drawText("WINDOW A " + n, x + 25, y + h / 4, p)
                    }
                    p.setColor(Color.WHITE)
                    p.setTextSize(50f)
                    c.drawText("Tap: open WINDOW B", 30f, h - 80, p)
                }

                override fun onTouchEvent(e: MotionEvent): Boolean {
                    if (
                        e.getAction() == MotionEvent.ACTION_UP &&
                            getIntent().getBooleanExtra("interactive", true)
                    )
                        startActivity(
                            Intent(this@CaptureFixtureActivity, SecondFixtureActivity::class.java)
                        )
                    return true
                }
            }
        )
    }
}
