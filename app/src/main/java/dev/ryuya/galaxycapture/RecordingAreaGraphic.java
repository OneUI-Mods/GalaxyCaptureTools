package dev.ryuya.galaxycapture;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Original glyphs within Samsung's native phone outline and selected-state background. */
final class RecordingAreaGraphic extends Drawable {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final boolean window;

  RecordingAreaGraphic(boolean window) {
    this.window = window;
    paint.setColor(Color.WHITE);
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    int save = canvas.save();
    canvas.translate(b.left, b.top);
    canvas.scale(b.width() / 48f, b.height() / 96f);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    if (window) {
      // One isolated window, unlike an application's multiple screens.
      canvas.drawRoundRect(9, 34, 39, 68, 3, 3, paint);
      canvas.drawLine(9, 42, 39, 42, paint);
      canvas.drawLine(13, 38, 15, 38, paint);
    } else {
      canvas.drawRoundRect(8, 16, 40, 80, 3, 3, paint);
      canvas.drawLine(8, 26, 40, 26, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawRoundRect(13, 33, 35, 45, 2, 2, paint);
      canvas.drawRoundRect(13, 50, 22, 63, 2, 2, paint);
      canvas.drawRoundRect(26, 50, 35, 63, 2, 2, paint);
    }
    canvas.restoreToCount(save);
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
