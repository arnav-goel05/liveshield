package com.liveshield.video.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class EncoderSettingsTest {
    @Test
    public void defaultsAreVideoOnlyAndBounded() {
        SanitizedVideoOutput.EncoderSettings defaults =
                SanitizedVideoOutput.EncoderSettings.defaults();

        assertEquals(4_000_000, defaults.bitrate());
        assertEquals(30, defaults.frameRate());
        assertEquals(2, defaults.keyFrameIntervalSeconds());
    }

    @Test
    public void everySettingMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new SanitizedVideoOutput.EncoderSettings(0, 30, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new SanitizedVideoOutput.EncoderSettings(1, 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new SanitizedVideoOutput.EncoderSettings(1, 30, 0));
    }
}
