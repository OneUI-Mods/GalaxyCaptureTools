package dev.ryuya.galaxycapture;

import android.text.Layout;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

/** A semantic line break, measured with the host's current font rather than a font-scale cutoff. */
final class MediaMicLabel implements ViewTreeObserver.OnGlobalLayoutListener {
  private static final String INLINE = "メディアとマイク";
  private static final String WRAPPED = "メディアと\nマイク";
  private final TextView label;
  private final View choice;
  private final int nativeWidth;
  private CharSequence text;
  private boolean enabled;

  MediaMicLabel(TextView view, View nativeChoice) {
    label = view;
    choice = nativeChoice;
    nativeWidth = nativeChoice.getLayoutParams().width;
    text = view.getText();
    view.getViewTreeObserver().addOnGlobalLayoutListener(this);
    view.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          public void onViewAttachedToWindow(View v) {
            v.getViewTreeObserver().removeOnGlobalLayoutListener(MediaMicLabel.this);
            v.getViewTreeObserver().addOnGlobalLayoutListener(MediaMicLabel.this);
            onGlobalLayout();
          }

          public void onViewDetachedFromWindow(View v) {
            if (v.getViewTreeObserver().isAlive())
              v.getViewTreeObserver().removeOnGlobalLayoutListener(MediaMicLabel.this);
          }
        });
  }

  void update(CharSequence value, boolean active) {
    text = value;
    enabled = active;
    onGlobalLayout();
  }

  public void onGlobalLayout() {
    // The native TextView is WRAP_CONTENT. Measuring against its own width would
    // alternate between one and two lines after each layout; use its container.
    View container = label.getParent() instanceof View v ? v : label;
    int available =
        container.getWidth()
            - container.getPaddingLeft()
            - container.getPaddingRight()
            - label.getCompoundPaddingLeft()
            - label.getCompoundPaddingRight();
    CharSequence next = text;
    String plain = text.toString().replace("\n", "").replace("\r", "");
    boolean japanese = enabled && INLINE.equals(plain);
    reservePhraseWidth(japanese, container);
    if (japanese && available > 0) {
      float width = Layout.getDesiredWidth(INLINE, label.getPaint());
      next = Math.ceil(width) <= available ? INLINE : WRAPPED;
    }
    if (!TextUtils.equals(label.getText(), next)) label.setText(next);
  }

  private void reservePhraseWidth(boolean active, View container) {
    var params = choice.getLayoutParams();
    int width = nativeWidth;
    if (active && nativeWidth == 0 && choice.getParent() instanceof ViewGroup row) {
      // Samsung uses three equally weighted MATCH_CONSTRAINT choices. At large
      // font sizes reserve just enough of that same native row for the first
      // phrase; the other two choices retain their native weighted constraints.
      int total = 0, count = 0;
      for (int i = 0; i < row.getChildCount(); i++) {
        View child = row.getChildAt(i);
        if (child.getClass() == choice.getClass()) {
          total += child.getWidth();
          count++;
        }
      }
      int inset =
          container.getPaddingLeft()
              + container.getPaddingRight()
              + label.getCompoundPaddingLeft()
              + label.getCompoundPaddingRight()
              + choice.getPaddingLeft()
              + choice.getPaddingRight();
      int required = (int) Math.ceil(Layout.getDesiredWidth("メディアと", label.getPaint())) + inset;
      if (count == 3 && total > 0 && required > total / 3 && required < total) width = required;
    }
    if (params.width != width) {
      params.width = width;
      choice.setLayoutParams(params);
    }
  }
}
