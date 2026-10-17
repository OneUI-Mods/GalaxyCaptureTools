package dev.ryuya.galaxycapture;

import java.nio.ByteBuffer;

/** Mix only valid PCM16 samples; unread tails must never replay a previous audio buffer. */
final class PcmAudioMixer {
  static void mix(short[] microphone, int micRead, boolean micMuted, short[] playback,
      int playbackRead, float playbackGain, int samples, ByteBuffer output) {
    int count = Math.min(Math.max(0, samples), output.remaining() / Short.BYTES);
    int micCount = micMuted ? 0 : Math.min(Math.max(0, micRead), microphone.length);
    int playbackCount = Math.min(Math.max(0, playbackRead), playback.length);
    float gain = Float.isFinite(playbackGain) ? Math.max(0, playbackGain) : 1;
    for (int n = 0; n < count; n++) {
      double value = (n < micCount ? microphone[n] : 0)
          + (n < playbackCount ? (double) playback[n] * gain : 0);
      output.putShort(
          (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value))));
    }
  }
}
