package com.liveshield.video.output;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class SanitizedOutputValueTest {
    @Test
    public void accessUnitCopiesInputAndReturnedPayload() {
        byte[] source = {1, 2, 3};
        SanitizedH264AccessUnit unit = new SanitizedH264AccessUnit(source, 123L, 7);

        source[0] = 99;
        byte[] returned = unit.payload();
        returned[1] = 88;

        assertArrayEquals(new byte[]{1, 2, 3}, unit.payload());
        assertEquals(123L, unit.presentationTimeUs());
        assertEquals(7, unit.codecFlags());
    }

    @Test
    public void codecConfigurationCopiesBothParameterSets() {
        byte[] sps = {1, 2};
        byte[] pps = {3, 4};
        H264CodecConfiguration configuration =
                new H264CodecConfiguration(720, 1280, sps, pps);

        sps[0] = 99;
        pps[0] = 99;

        assertArrayEquals(new byte[]{1, 2}, configuration.sequenceParameterSet());
        assertArrayEquals(new byte[]{3, 4}, configuration.pictureParameterSet());
        byte[] returnedSps = configuration.sequenceParameterSet();
        byte[] returnedPps = configuration.pictureParameterSet();
        returnedSps[0] = 88;
        returnedPps[0] = 88;
        assertArrayEquals(new byte[]{1, 2}, configuration.sequenceParameterSet());
        assertArrayEquals(new byte[]{3, 4}, configuration.pictureParameterSet());
    }

    @Test
    public void invalidTimestampsDimensionsAndEmptyConfigurationAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SanitizedH264AccessUnit(new byte[0], -1L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new H264CodecConfiguration(0, 1, new byte[]{1}, new byte[]{2}));
        assertThrows(IllegalArgumentException.class,
                () -> new H264CodecConfiguration(1, 1, new byte[0], new byte[]{2}));
        assertThrows(IllegalArgumentException.class,
                () -> new H264CodecConfiguration(1, 1, new byte[]{1}, new byte[0]));
        assertThrows(NullPointerException.class,
                () -> new H264CodecConfiguration(1, 1, null, new byte[]{2}));
    }
}
