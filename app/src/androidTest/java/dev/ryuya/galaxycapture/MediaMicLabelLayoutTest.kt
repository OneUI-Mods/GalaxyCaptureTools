package dev.ryuya.galaxycapture

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.test.platform.app.InstrumentationRegistry
import dev.ryuya.galaxycapture.hook.Reflect
import kotlin.jvm.JvmStatic
import kotlin.math.round
import org.junit.Assert.*
import org.junit.Test

/* Measures Samsung's real control and font at several window widths and accessibility sizes. */
open class MediaMicLabelLayoutTest {
    @Test
    open fun nativeLabelPreservesWordsAndUsesOneLineWhenItFits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync({
            try {
                val target = instrumentation.getTargetContext()
                val host =
                    target.createPackageContext(
                        "com.samsung.android.app.smartcapture",
                        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                    )
                val nativeChoice =
                    host
                        .getClassLoader()
                        .loadClass(
                            "com.samsung.android.app.smartcapture.screenrecorder.view.EnhancedRadioButton"
                        )
                for (scale in floatArrayOf(.85f, 1f, 1.3f, 1.5f, 2f)) {
                    val config = Configuration(host.getResources().getConfiguration())
                    config.fontScale = scale
                    val sized =
                        ContextThemeWrapper(
                            host.createConfigurationContext(config),
                            host
                                .getResources()
                                .getIdentifier(
                                    "RecorderDialogAlertTheme",
                                    "style",
                                    host.getPackageName(),
                                ),
                        )
                    for (dp in intArrayOf(280, 360, 600, 900)) {
                        val row = ConstraintLayout(target)
                        val choices = arrayOfNulls<View>(3)
                        var label: TextView? = null
                        for (i in choices.indices) {
                            val choice: View? =
                                nativeChoice.getConstructor(Context::class.java).newInstance(sized)
                                    as View?
                            choices[i] = choice
                            choice!!.setId(View.generateViewId())
                            val text: TextView? =
                                Reflect.getObjectField(choice!!, "textView") as TextView?
                            text!!.setText(arrayOf<String>("なし", "メディア", "メディアとマイク")[i])
                            text!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            if (i == 2) label = text
                        }
                        for (i in choices.indices) {
                            val p = LayoutParams(0, -2)
                            p.topToTop = 0
                            p.horizontalWeight = 1f
                            p.rightMargin = MediaMicLabelLayoutTest.px(sized, 4)
                            p.leftMargin = p.rightMargin
                            if (i == 0) p.leftToLeft = 0
                            else p.leftToRight = choices[i - 1]!!.getId()
                            if (i == 2) p.rightToRight = 0
                            else p.rightToLeft = choices[i + 1]!!.getId()
                            row.addView(choices[i], p)
                        }
                        val wrapper = MediaMicLabel(label!!, choices[2]!!)
                        wrapper.update("メディアとマイク", true)
                        val width = MediaMicLabelLayoutTest.px(sized, dp)
                        for (pass in 0..7) {
                            row.measure(
                                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(
                                    MediaMicLabelLayoutTest.px(sized, 1000),
                                    MeasureSpec.AT_MOST,
                                ),
                            )
                            row.layout(0, 0, width, row.getMeasuredHeight())
                            wrapper.onGlobalLayout()
                        }
                        val context: String = "fontScale=" + scale + "; width=" + dp
                        val layout = label!!.getLayout()
                        assertNotNull(context, layout)
                        assertTrue(context, layout.getLineCount() <= 2)
                        for (line in 0 until layout.getLineCount()) assertEquals(
                            context,
                            0,
                            layout.getEllipsisCount(line),
                        )
                        if (layout.getLineCount() == 2) {
                            val first =
                                label!!
                                    .getText()
                                    .subSequence(0, layout.getLineEnd(0))
                                    .toString()
                                    .trim { it <= ' ' }
                            assertEquals(context, "メディアと", first)
                            assertEquals(
                                context,
                                "マイク",
                                label!!
                                    .getText()
                                    .subSequence(layout.getLineStart(1), layout.getLineEnd(1))
                                    .toString(),
                            )
                        } else assertEquals(context, "メディアとマイク", label!!.getText().toString())
                        if (dp == 900) assertEquals(context, 1, layout.getLineCount())
                        wrapper.update("マイクのみ", true)
                        assertEquals(context, "マイクのみ", label!!.getText().toString())
                        assertEquals(context, 0, choices[2]!!.getLayoutParams().width)
                        wrapper.update("メディアとマイク", false)
                        assertEquals(context, "メディアとマイク", label!!.getText().toString())
                    }
                }
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            } catch (e: NameNotFoundException) {
                throw AssertionError(e)
            }
        })
    }

    companion object {
        @JvmStatic
        private fun px(c: Context, dp: Int): Int {
            return Math.round(dp * c.getResources().getDisplayMetrics().density)
        }
    }
}
