# Galaxy Capture Tools

[日本語](README.md) · English

An experimental Vector module extending Android's stock and Samsung screen-recording and screenshot features.

## Automatic startup after boot

Automatic startup after device boot can be switched on or off in settings. The default is on when no choice has been saved; an existing off choice is preserved. Startup only restores configured behavior and does not override feature switches, permissions, or lock-state restrictions. The switch is in the About tab. Turning it off keeps on-demand setup available when a supported app opens. Settings preparation waits for the first unlock and adds no resident service or polling.

## First unlock after boot

Hook initialization that needs settings or cached discovery results waits for the first user unlock after boot. A later ordinary screen lock does not repeat initialization. Existing screen-off power-saving and security behavior is preserved. Unlock delivery is event-driven, with no polling or new resident service.

## Features

- Keep the output dimensions chosen at recording start and rotate captured content by 90 degrees as the device rotates, reducing black bars.
- Optionally ignore 180-degree orientation changes, independently of black-bar correction.
- Select an app or window from Samsung's **Area to record**, with a dedicated recent-app/list/search screen.
- Optionally lock capture to the selected main window.
- For app capture, choose audio from only that app or the whole device.
- Optionally include call playback in internal audio. Shared by Samsung and stock Android recording; defaults OFF.
- Media-plus-microphone recording, the speaker-mode microphone-priority branch, and Samsung recording-volume controls.
- Separately adjust the recording toolbar, countdown, and duplicate right-side mirroring icon.
- Move Samsung's recording indicator to the right while preserving its tap-to-stop action. Portrait-oriented; defaults OFF.
- Mitigate control overlays appearing during rotation or repeated screenshots.
- Separate screenshot and screen-recording restriction overrides, both OFF by default.
- Synchronize the companion app with **Custom settings** inside Samsung Capture settings.

## Requirements

Separate compatibility paths support Galaxy S26 Ultra (SM-S948Q) on Android 16 / One UI 8.5 (AZHL) and Android 17 / One UI 9 (BZI9). A supported root environment and Vector with libxposed API 102 are required. See [compatibility information (Japanese)](docs/compatibility/android17.md) for supported Samsung Capture and system versions.

## Installation and use

1. Install the signed release APK from [Releases](https://github.com/OneUI-Mods/GalaxyCaptureTools/releases). Updates to the same package can be installed in place.
2. Enable the module in Vector and select Android System, SystemUI, and Samsung Capture. Initial loading of system-side hooks requires restarting the system.
3. Enable the master switch and select the individual features you want. With the master switch OFF, all extensions stop while individual settings remain saved.
4. Settings apply to the next recording or screenshot. Make a short video/audio test before an important recording.

The app has **Common**, **Galaxy**, **Status**, and **About** sections. Common settings apply to both stock Android and Galaxy recording; Galaxy settings are specific to Samsung Capture. In system Settings, open **Advanced features → Screenshots and screen recordings → Custom settings**.

## Limitations and disabling

Rotation correction is not applied to Samsung's **Part of screen** mode. Aspect-ratio changes unrelated to rotation may retain empty space to avoid cropping content. Leaving a locked capture window produces black or the preceding frame rather than exposing another app. Associated menus and popups may still appear.

Toggle **Common → Audio and display → Include call audio in internal audio**, or the synchronized switch under Samsung Capture's **Custom settings → Audio**. When ON, playback classified as call audio is included; when OFF, it is excluded from internal capture. It defaults OFF and applies from the next recording. Selected-app audio scope and microphone mode are preserved. Android 17 synthetic-audio tests covered selected-app/whole-device Samsung capture with the option ON/OFF and whole-device stock Android capture with it ON/OFF. Actual Discord/Rain call paths and Android 16 call capture have not been verified; this feature remains experimental.

Cellular calls, Bluetooth routes, and apps with custom call-audio paths may not be captured. Audio classified as ordinary media, or sound picked up acoustically by the microphone from the speaker, is not excluded solely by turning call-audio capture OFF.

Selected-app audio still follows the app's playback-capture restrictions. Some DRM-protected rendering may remain black even with a restriction override enabled. Recording-volume controls affect capture, not the device's playback volume.

Turn the master switch OFF to stop the extensions. For complete removal, disable the module in Vector and reload target processes. If crash protection activates, resolve the cause before resetting it.

## Development and licenses

Build instructions, architecture, and verification are in the [developer guide (Japanese)](docs/development.md). Report problems through [Issues](https://github.com/OneUI-Mods/GalaxyCaptureTools/issues), including device, OS and target-app versions, and reproduction steps.

See [LICENSE](LICENSE) for the app's license and the in-app or bundled notices for dependencies. This is not an official Samsung app.

[OneUI Mods app catalog](https://github.com/OneUI-Mods)

## String compatibility

Certain whitespace in capture-target search now follows the previous behavior again. License search uses the same matching rules.

## Compatibility after updates

After a target-app update, open this module once to complete preparation, then reopen the target. Discovery results are stored in this module's own internal app data and reused while unchanged. No other OneUI Mods app is required. Features stop applying when a unique compatible target cannot be confirmed. Compatibility with every OS or app update is not guaranteed.
