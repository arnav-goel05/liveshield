package com.liveshield.app.setup;

import java.util.List;
import java.util.Objects;

/** Pure setup rule: only a currently presented fresh session track may confer readiness. */
final class FreshHostSelection {
    private FreshHostSelection() {
    }

    static boolean isSelectedAndFresh(List<SelectableFace> faces, Long selectedTrack) {
        Objects.requireNonNull(faces, "faces");
        if (selectedTrack == null) {
            return false;
        }
        return faces.stream().anyMatch(face ->
                face.fresh() && face.trackId() == selectedTrack.longValue());
    }
}
