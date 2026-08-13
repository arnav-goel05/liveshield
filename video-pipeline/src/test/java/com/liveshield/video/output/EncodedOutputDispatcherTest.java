package com.liveshield.video.output;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class EncodedOutputDispatcherTest {
    private static final int CONFIG_FLAG = 2;

    @Test
    public void copiesOnlyBufferInfoRangeAndPreservesPtsAndFlags() {
        CollectingSink sink = new CollectingSink();
        EncodedOutputDispatcher dispatcher = new EncodedOutputDispatcher(sink, CONFIG_FLAG);
        ByteBuffer codecOwned = ByteBuffer.wrap(new byte[]{99, 10, 11, 12, 88});

        dispatcher.dispatch(codecOwned, 1, 3, 42L, 1);
        codecOwned.put(1, (byte) 77);

        assertEquals(1, sink.units.size());
        SanitizedH264AccessUnit copied = sink.units.get(0);
        assertArrayEquals(new byte[]{10, 11, 12}, copied.payload());
        assertEquals(42L, copied.presentationTimeUs());
        assertEquals(1, copied.codecFlags());
    }

    @Test
    public void codecConfigurationDoesNotResetOrAdvanceMediaPts() {
        CollectingSink sink = new CollectingSink();
        EncodedOutputDispatcher dispatcher = new EncodedOutputDispatcher(sink, CONFIG_FLAG);

        dispatcher.dispatch(ByteBuffer.wrap(new byte[]{1}), 0, 1, 100L, 0);
        dispatcher.dispatch(ByteBuffer.wrap(new byte[]{2}), 0, 1, 0L, CONFIG_FLAG);
        dispatcher.dispatch(ByteBuffer.wrap(new byte[]{3}), 0, 1, 101L, 0);

        assertEquals(3, sink.units.size());
    }

    @Test
    public void regressingMediaPtsAndInvalidRangesFailClosed() {
        CollectingSink sink = new CollectingSink();
        EncodedOutputDispatcher dispatcher = new EncodedOutputDispatcher(sink, CONFIG_FLAG);
        dispatcher.dispatch(ByteBuffer.wrap(new byte[]{1}), 0, 1, 100L, 0);

        assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(ByteBuffer.wrap(new byte[]{2}), 0, 1, 99L, 0));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(ByteBuffer.wrap(new byte[]{2}), 1, 1, 101L, 0));
    }

    private static final class CollectingSink implements SanitizedVideoSink {
        private final List<SanitizedH264AccessUnit> units = new ArrayList<>();

        @Override
        public void onCodecConfiguration(H264CodecConfiguration configuration) {
        }

        @Override
        public void onAccessUnit(SanitizedH264AccessUnit accessUnit) {
            units.add(accessUnit);
        }
    }
}
