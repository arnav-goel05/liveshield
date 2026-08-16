# LiveShield protected setup — design QA

## Comparison target

- Source visual truth: `/Users/arnav/.codex/generated_images/019ff627-d6a2-7df3-bac7-02dbdeb5541d/exec-16e255ba-a8a1-4fdb-818f-33a8c2425c46.png`
- Implementation screenshot: `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/setup-redesign-api36.png`
- Full comparison evidence: `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/source-vs-setup-redesign-api36.png`
- Focused controls comparison: `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/source-vs-setup-controls-api36.png`
- Earlier implementation evidence: `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/setup-redesign-iteration1-api36.png`
- Source pixels: 853 × 1844.
- Implementation pixels: 1080 × 2400 on the Android API 36 ARM64 emulator at 420 dpi. CSS size and browser device-scale factor do not apply to this native Android screen.
- Density normalization: the implementation was proportionally scaled to 830 × 1844 and placed beside the unchanged 853 × 1844 source. Android-owned status/navigation chrome was retained but excluded from visual findings.
- State: the source illustrates a fully configured session. The implementation deliberately shows the truthful live initial state: camera running, QR protection on, no selected face, no private words, no zones, no destination, and Start disabled. State-dependent labels and button color are therefore expected differences rather than design drift.

## Findings

No actionable P0, P1, or P2 visual differences remain.

- [P3] The emulator's synthetic camera scene is visually unlike the aspirational room scene in the mockup.
  - Location: protected preview.
  - Evidence: the source uses a staged creator scene; the implementation displays the emulator's real CameraX feed through the sanitized renderer.
  - Impact: none on layout fidelity or product behavior.
  - Follow-up: judge subject matter on a physical camera; do not replace the real preview with a decorative image.
- [P3] The unconfigured destination uses a generic camera icon rather than TikTok branding.
  - Location: destination card.
  - Evidence: the source is already configured for TikTok; the implementation truthfully says “Choose destination.”
  - Impact: no functional loss. Once external broadcast is configured, the title becomes “TikTok LIVE” and the status becomes “Selected.”
  - Follow-up: use an approved official TikTok asset only if brand approval and redistribution rights are supplied.

## Required fidelity surfaces

- Fonts and typography: native Android sans preserves the mockup's bold section heading, regular row labels, compact teal statuses, wrapping, and optical hierarchy. No visible clipping or truncation remains.
- Spacing and layout rhythm: the sanitized preview fills the app-owned top area beneath the Android status bar. The title, rounded four-row card, separate destination card, and primary action follow the source order and fit in the initial viewport. Row height, indicator scale, dividers, corner radii, and horizontal margins were tightened after the first pass.
- Colors and visual tokens: white, near-black ink, teal, pale aqua, lavender, yellow, coral, divider grey, and disabled grey map directly to the reference. Contrast remains clear in both enabled and disabled states.
- Image quality and asset fidelity: the hero is the actual sanitized camera surface, not a placeholder raster. Existing LiveShield and Material-style vector assets remain sharp at device density. The emulator camera's black side bars are the real aspect-fit output and preserve coordinate-safe mask mapping.
- Copy and content: the section and row copy matches the source. Dynamic counts and states are truthful: `Add`, `Draw`, `On/Off`, selected face, word count, zone count, destination type, and destination selection status.
- Icons: face, QR, private words, privacy zone, destination, verified badge, checks, and chevrons share consistent sizing, tint, and alignment.
- Accessibility and responsiveness: each row is at least a 56 dp tap target, the preview and overlays retain their production accessibility descriptions, the real QR checkbox remains semantic, and the hidden visual readiness message is appended to the Start button's accessibility description.

## Full-view and focused comparison evidence

The combined full-view image was inspected for preview proportion, section hierarchy, card placement, action visibility, typography, palette, and overall density. The focused controls comparison was separately inspected because row icons, indicator scale, text alignment, dividers, statuses, and the destination card were too small to judge precisely from the full view alone.

## Interaction and implementation verification

- The protected preview is the existing sanitized CameraX/rendering surface; face and privacy-zone overlays remain attached above it.
- Tapping the face row returns to the live preview for face selection.
- Tapping the QR row changed the production session flag from `On` to `Off`; a second tap restored `On`.
- Tapping Private words revealed the real session-only input and Add control.
- Tapping Privacy zones enabled the actual draw overlay and changed the row state to `Drawing`.
- Tapping the destination card revealed the existing local relay and TikTok external broadcast forms.
- Start remains connected to `LiveSessionCoordinator` and disabled until the real readiness contract is satisfied.
- No app FATAL, ANR, or assertion failure appeared during the final visual pass. The emulator emitted only its known `mapper.ranchu` unsupported-metadata diagnostics.
- App JVM tests, debug assembly, debug/release Java compilation, Android-test compilation, Checkstyle, and debug/release lint passed.

## Comparison history

### Pass 1 — blocked

- [P1] The retained 75% preview height made the protection controls excessively far below the camera and did not match the selected mockup after its empty top region was assigned to the preview.
- [P2] The four protection rows, pastel indicators, destination row, badge, and Start button were materially oversized relative to the reference.
- [P2] The protected-preview shield inherited a dark tint rather than the source's white icon.

Fixes:

- Sized the real preview to 48% of the usable viewport, filling the source's former top whitespace while retaining a scrollable screen.
- Reduced option rows to 56 dp, indicators to 34 dp, icons to 24 dp, the destination row to 56 dp, and the Start action to 52 dp.
- Applied a white badge drawable tint and tightened badge typography and padding.
- Removed the separate visible readiness block from the composition while preserving its current message in the Start button's accessibility description.

### Pass 2 — passed

- Post-fix evidence: `setup-redesign-api36.png`, `source-vs-setup-redesign-api36.png`, and `source-vs-setup-controls-api36.png`.
- All app-owned content is visible in the initial viewport, the top is occupied by the live protected preview, and no actionable P0/P1/P2 mismatch remains.

## Follow-up polish

- Re-capture the same screen on the connected physical phone for a more representative camera subject; this is not required for layout acceptance.

## Final result

final result: passed
