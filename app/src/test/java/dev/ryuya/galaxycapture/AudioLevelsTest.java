package dev.ryuya.galaxycapture;
import static org.junit.Assert.*;
import org.junit.Test;

public class AudioLevelsTest {
  @Test public void inputsHaveIndependentLevelsBeforeNativeMixAndClipping() {
    short[] media = {32000, -32000, 8000, -8000};
    short[] mic = {12000, -12000, 4000, -4000};
    AudioLevels.scale(media, 4, 25);
    AudioLevels.scale(mic, 4, 50);
    assertArrayEquals(new short[] {8000, -8000, 2000, -2000}, media);
    assertArrayEquals(new short[] {6000, -6000, 2000, -2000}, mic);
  }
  @Test public void silenceIsExactAndNeverReadsBeyondValidNativeSamples() {
    short[] input = {-32768, 32767, 1234, 5678};
    AudioLevels.scale(input, 2, 0);
    assertArrayEquals(new short[] {0, 0, 1234, 5678}, input);
    AudioLevels.scale(input, -1, 0);
    assertEquals(1234, input[2]);
  }
  @Test public void fullLevelIsBitExactAndOutOfRangeValuesCannotAmplifyOrInvert() {
    short[] input = {-32768, 32767, -1, 0, 1};
    short[] original = input.clone();
    AudioLevels.scale(input, 999, 150);
    assertArrayEquals(original, input);
    AudioLevels.scale(input, 999, -20);
    assertArrayEquals(new short[5], input);
    assertEquals(100, AudioLevels.clamp(1000));
    assertEquals(0, AudioLevels.clamp(-1));
  }
}
