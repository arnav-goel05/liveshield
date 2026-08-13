package com.liveshield.privacy.policy;

import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.Set;

/** Read-only policy view of session-scoped watchlists and complete privacy zones. */
public interface SessionPrivacyConfigurationView {
    Set<String> normalizedWatchlistTerms();

    List<NormalizedRect> activePrivacyZones();

    boolean zonesSafelyTransformed();
}
