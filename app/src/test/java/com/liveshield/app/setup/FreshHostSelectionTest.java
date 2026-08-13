package com.liveshield.app.setup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import org.junit.Test;

public final class FreshHostSelectionTest {
    private static final NormalizedRect BOUNDS = new NormalizedRect(0.1, 0.1, 0.4, 0.5);

    @Test
    public void acceptsMatchingFreshSessionTrack() {
        assertTrue(FreshHostSelection.isSelectedAndFresh(
                List.of(new SelectableFace(7L, BOUNDS, true)), 7L));
    }

    @Test
    public void rejectsMatchingStaleTrack() {
        assertFalse(FreshHostSelection.isSelectedAndFresh(
                List.of(new SelectableFace(7L, BOUNDS, false)), 7L));
    }

    @Test
    public void rejectsMissingOrUnknownTrack() {
        List<SelectableFace> faces = List.of(new SelectableFace(7L, BOUNDS, true));

        assertFalse(FreshHostSelection.isSelectedAndFresh(faces, null));
        assertFalse(FreshHostSelection.isSelectedAndFresh(faces, 8L));
    }
}
