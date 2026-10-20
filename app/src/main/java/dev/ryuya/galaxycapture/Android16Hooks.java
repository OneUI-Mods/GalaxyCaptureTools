package dev.ryuya.galaxycapture;

/** Frozen Android 16 admission rules; hook sources are recorded in docs/compatibility. */
final class Android16Hooks {
  static final String DIALOG_BUILDER = "M4.c";
  static final String DIALOG_CREATE = "f";
  static final String BUILD = "BP4A.251205.006.S948QOPS1AZHL";

  static boolean supports(
      int sdk, String manufacturer, String model, String device, String display) {
    return sdk == 36 && "samsung".equalsIgnoreCase(manufacturer) && BUILD.equals(display);
  }
}
