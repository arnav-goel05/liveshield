package com.liveshield.privacy.decision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.ProtectionAction;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class FramePrivacyDecisionTest {
    @Test
    public void constructionForFrameDefaultsUnconditionallyToFullShield() {
        FramePrivacyDecision decision = new FramePrivacyDecision(FrameTimestamp.ofNanos(10));

        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, decision.status());
        assertEquals(FramePrivacyDecision.Basis.MISSING, decision.basis());
        assertTrue(decision.regions().isEmpty());
    }

    @Test
    public void regionalDecisionDefensivelyCopiesRegions() {
        List<ProtectedRegion> regions = new ArrayList<>();
        regions.add(new ProtectedRegion(
                FindingCategory.FACE,
                List.of(new NormalizedRect(0.1, 0.1, 0.2, 0.2)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC));

        FramePrivacyDecision decision = FramePrivacyDecision.regionalSafe(
                FrameTimestamp.ofNanos(10),
                regions,
                FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(20));
        regions.clear();

        assertEquals(1, decision.regions().size());
    }
}
