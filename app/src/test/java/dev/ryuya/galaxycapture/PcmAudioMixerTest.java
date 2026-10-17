package dev.ryuya.galaxycapture;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import org.junit.Test;

public class PcmAudioMixerTest {
  @Test public void combinesAppPlaybackAndMicrophoneOnBothStereoChannels() {
    ByteBuffer b = ByteBuffer.allocate(8);
    PcmAudioMixer.mix(new short[]{100, -100, 200, -200}, 4, false,
        new short[]{1000, -1000, 3000, -3000}, 4, .5f, 4, b);
    b.flip();
    assertEquals(600, b.getShort()); assertEquals(-600, b.getShort());
    assertEquals(1700, b.getShort()); assertEquals(-1700, b.getShort());
  }
  @Test public void microphoneMuteKeepsAppAudio() {
    ByteBuffer b = ByteBuffer.allocate(2);
    PcmAudioMixer.mix(new short[]{1000}, 1, true, new short[]{2400}, 1, 1, 1, b);
    assertEquals(2400, b.getShort(0));
  }
  @Test public void clipsInsteadOfWrapping() {
    ByteBuffer b = ByteBuffer.allocate(4);
    PcmAudioMixer.mix(new short[]{30000, -30000}, 2, false,
        new short[]{30000, -30000}, 2, 1, 2, b);
    assertEquals(32767, b.getShort(0)); assertEquals(-32768, b.getShort(2));
  }
  @Test public void shortReadsAndErrorsDoNotReplayOldSamples() {
    ByteBuffer b = ByteBuffer.allocate(8);
    PcmAudioMixer.mix(new short[]{7, 9999, 9999, 9999}, 1, false,
        new short[]{8, 9, 9999, 9999}, 2, 1, 4, b);
    assertEquals(15, b.getShort(0)); assertEquals(9, b.getShort(2));
    assertEquals(0, b.getShort(4)); assertEquals(0, b.getShort(6));
    b.clear();
    PcmAudioMixer.mix(new short[]{9999}, -3, false, new short[]{32}, 1, 1, 1, b);
    assertEquals(32, b.getShort(0));
  }
  @Test public void respectsDestinationPositionAndRemainingSpace() {
    ByteBuffer b = ByteBuffer.allocate(6); b.position(2); b.limit(4);
    PcmAudioMixer.mix(new short[]{10, 20}, 2, false, new short[]{30, 40}, 2, 1, 2, b);
    assertEquals(4, b.position()); assertEquals(40, b.getShort(2));
    assertEquals(0, b.getShort(0));
  }
}
