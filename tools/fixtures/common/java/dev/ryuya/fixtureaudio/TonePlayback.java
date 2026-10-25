package dev.ryuya.fixtureaudio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Internal APKs only: a steady tone whose usage and UID can be measured independently. */
public final class TonePlayback implements AutoCloseable {
  private AudioTrack track;

  public TonePlayback(int usage, int frequency) {
    short[] samples = new short[48000];
    for (int frame = 0; frame < samples.length; frame++)
      samples[frame] = (short) (1400 * Math.sin(2 * Math.PI * frequency * frame / 48000));
    track =
        new AudioTrack.Builder()
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(
                        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                            ? AudioAttributes.CONTENT_TYPE_SPEECH
                            : AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build())
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
            .setBufferSizeInBytes(samples.length * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build();
    try {
      if (track.write(samples, 0, samples.length) != samples.length
          || track.setLoopPoints(0, samples.length, -1) != AudioTrack.SUCCESS)
        throw new IllegalStateException("Fixture tone was not initialized");
      track.play();
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  @Override
  public void close() {
    if (track == null) return;
    try {
      track.stop();
    } finally {
      track.release();
      track = null;
    }
  }
}
