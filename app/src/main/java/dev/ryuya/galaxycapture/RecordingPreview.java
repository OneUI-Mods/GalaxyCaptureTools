package dev.ryuya.galaxycapture;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import com.google.android.material.color.MaterialColors;

/** Illustrates rotation correction without using any captured screen content. */
final class RecordingPreview extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  RecordingPreview(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float d = getResources().getDisplayMetrics().density;
    float cx = getWidth() / 2f, cy = getHeight() / 2f;
    int ink = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
    int surface =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer);
    paint.setColor(ink);
    canvas.drawRoundRect(
        cx - 126 * d, cy - 26 * d, cx - 34 * d, cy + 26 * d, 12 * d, 12 * d, paint);
    paint.setColor(surface);
    canvas.drawRoundRect(cx - 121 * d, cy - 21 * d, cx - 39 * d, cy + 21 * d, 8 * d, 8 * d, paint);
    paint.setColor(ink);
    canvas.drawCircle(cx - 98 * d, cy - 7 * d, 6 * d, paint);
    paint.setStrokeWidth(2 * d);
    canvas.drawLine(cx - 10 * d, cy, cx + 18 * d, cy, paint);
    canvas.drawLine(cx + 10 * d, cy - 8 * d, cx + 18 * d, cy, paint);
    canvas.drawLine(cx + 10 * d, cy + 8 * d, cx + 18 * d, cy, paint);
    canvas.drawRoundRect(
        cx + 48 * d, cy - 44 * d, cx + 100 * d, cy + 44 * d, 12 * d, 12 * d, paint);
    var preferences = getContext().getSharedPreferences("config", 0);
    boolean corrected =
        preferences.getBoolean("enabled", false) && preferences.getBoolean("rotation", true);
    paint.setColor(corrected ? surface : Color.BLACK);
    canvas.drawRoundRect(cx + 53 * d, cy - 39 * d, cx + 95 * d, cy + 39 * d, 8 * d, 8 * d, paint);
    if (!corrected) {
      paint.setColor(surface);
      canvas.drawRect(cx + 53 * d, cy - 13 * d, cx + 95 * d, cy + 13 * d, paint);
    }
    paint.setColor(ink);
    canvas.drawCircle(cx + 65 * d, cy - (corrected ? 21 : 5) * d, corrected ? 6 * d : 3 * d, paint);
  }
}
