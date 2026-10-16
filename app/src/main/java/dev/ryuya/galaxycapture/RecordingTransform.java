package dev.ryuya.galaxycapture;

/** SurfaceControl affine transform, in its (dsdx, dtdx, dsdy, dtdy) order. */
final class RecordingTransform {
  record Transform(float a, float b, float c, float d, float x, float y) {
    float mapX(float x, float y) {
      return a * x + c * y + this.x;
    }

    float mapY(float x, float y) {
      return b * x + d * y + this.y;
    }
  }

  static Transform fit(int width, int height, int outputWidth, int outputHeight, int quarterTurns) {
    if (width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0)
      throw new IllegalArgumentException("Empty recording geometry");
    int q = Math.floorMod(quarterTurns, 4);
    int rw = (q % 2 == 0) ? width : height, rh = (q % 2 == 0) ? height : width;
    float s = Math.min((float) outputWidth / rw, (float) outputHeight / rh);
    float x = (outputWidth - rw * s) / 2, y = (outputHeight - rh * s) / 2;
    return switch (q) {
      case 1 -> new Transform(0, s, -s, 0, x + height * s, y);
      case 2 -> new Transform(-s, 0, 0, -s, x + width * s, y + height * s);
      case 3 -> new Transform(0, -s, s, 0, x, y + width * s);
      default -> new Transform(s, 0, 0, s, x, y);
    };
  }
}
