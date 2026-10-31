package dev.ryuya.galaxycapture

import android.text.Layout
import android.text.TextUtils
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.TextView
import kotlin.math.ceil

/* A semantic line break, measured with the host's current font rather than a font-scale cutoff. */
class MediaMicLabel(view: TextView, nativeChoice: View) : OnGlobalLayoutListener {
    private val label: TextView
    private val choice: View
    private val nativeWidth: Int
    private var text: CharSequence?
    private var enabled: Boolean = false

    init {
        label = view
        choice = nativeChoice
        nativeWidth = nativeChoice.getLayoutParams().width
        text = view.getText()
        view.getViewTreeObserver().addOnGlobalLayoutListener(this)
        view.addOnAttachStateChangeListener(
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.getViewTreeObserver().removeOnGlobalLayoutListener(this@MediaMicLabel)
                    v.getViewTreeObserver().addOnGlobalLayoutListener(this@MediaMicLabel)
                    onGlobalLayout()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    if (v.getViewTreeObserver().isAlive())
                        v.getViewTreeObserver().removeOnGlobalLayoutListener(this@MediaMicLabel)
                }
            }
        )
    }

    fun update(value: CharSequence?, active: Boolean) {
        text = value
        enabled = active
        onGlobalLayout()
    }

    override fun onGlobalLayout() {
        // The native TextView is WRAP_CONTENT. Measuring against its own width would
        // alternate between one and two lines after each layout; use its container.
        val container: View? = (label.parent as? View) ?: label
        val available: Int =
            (container!!.getWidth() -
                container!!.getPaddingLeft() -
                container!!.getPaddingRight() -
                label.getCompoundPaddingLeft() -
                label.getCompoundPaddingRight())
        var next: CharSequence? = text
        val plain = text.toString().replace("\n", "").replace("\r", "")
        val japanese: Boolean = enabled && MediaMicLabel.INLINE == plain
        reservePhraseWidth(japanese, container!!)
        if (japanese && available > 0) {
            val width = Layout.getDesiredWidth(MediaMicLabel.INLINE, label.getPaint())
            next = if (ceil(width) <= available) MediaMicLabel.INLINE else MediaMicLabel.WRAPPED
        }
        if (!TextUtils.equals(label.getText(), next)) label.setText(next)
    }

    private fun reservePhraseWidth(active: Boolean, container: View) {
        val params = choice.getLayoutParams()
        var width: Int = nativeWidth
        if (active && nativeWidth == 0 && choice.getParent() is ViewGroup) {
            // Samsung uses three equally weighted MATCH_CONSTRAINT choices. At large
            // font sizes reserve just enough of that same native row for the first
            // phrase; the other two choices retain their native weighted constraints.
            val row = choice.parent as ViewGroup
            var total: Int = 0
            var count: Int = 0
            for (i in 0 until row.getChildCount()) {
                val child = row.getChildAt(i)
                if (child.javaClass == choice.javaClass) {
                    total += child.getWidth()
                    count++
                }
            }
            val inset: Int =
                (container.getPaddingLeft() +
                    container.getPaddingRight() +
                    label.getCompoundPaddingLeft() +
                    label.getCompoundPaddingRight() +
                    choice.getPaddingLeft() +
                    choice.getPaddingRight())
            val required: Int =
                (ceil(Layout.getDesiredWidth("メディアと", label.getPaint()))).toInt() + inset
            if (count == 3 && total > 0 && required > total / 3 && required < total)
                width = required
        }
        if (params.width != width) {
            params.width = width
            choice.setLayoutParams(params)
        }
    }

    companion object {
        private const val INLINE: String = "メディアとマイク"
        private const val WRAPPED: String = "メディアと\nマイク"
    }
}
