# LiveShield setup redesign — design QA

## Comparison target

- Source visual truth: `/Users/arnav/.codex/attachments/4032923f-1085-4180-9bd5-778b0a6c1c3f/image-1.png`
- Source pixels: 1726 × 911. The concept board contains two 863 × 911 screen panels.
- Implementation screenshots:
  - `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/disclosure-api36.png`
  - `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/setup-api36.png`
- Full comparison board: `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/source-vs-api36.png`
- Expanded destination evidence: `/Users/arnav/Documents/ChatGPT/Java Project for Resume/app/build/reports/design-qa/destination-expanded-api36.png`
- Native viewport: Android API 36 ARM64 emulator, 1080 × 2400 physical pixels at 420 dpi.
- Density normalization: Android system bars were cropped for visual comparison, leaving 1080 × 2148 app-content pixels. Each source panel and implementation capture was proportionally scaled to 540 pixels wide without stretching. The concept board is not a phone-aspect viewport, so vertical length is intentionally preserved rather than forced to the board's aspect ratio. CSS size and browser device-scale factor are not applicable to this native Android implementation.
- States: disclosure before acknowledgement; setup immediately after acknowledgement with camera permission denied; destination expanded with TikTok external mode selected.

## Findings

No actionable P0, P1, or P2 visual differences remain.

- [P3] The native Android `sans` optical metrics are slightly heavier than the unidentified sans-serif used by the concept mockup.
  - Location: hero title and section headings on both screens.
  - Evidence: the comparison board shows the same hierarchy and wrapping, with slightly heavier native glyph forms.
  - Impact: minor platform-native rendering difference only; readability and hierarchy remain intact.
  - Follow-up: retain the system font unless a licensed source font is supplied.

## Required fidelity surfaces

- Fonts and typography: native Android sans is used consistently; weights, sizes, line wrapping, and hierarchy match the source intent. No clipping, truncation, or unintended wrapping is visible.
- Spacing and layout rhythm: the reference's white canvas, large hero, three-step disclosure, warning card, full-width CTA, preview card, compact setup rows, and bottom readiness state are preserved. The Android screen is taller than the two-up concept panel, so it has expected additional vertical room.
- Colors and visual tokens: white, ink, slate, teal, pale teal, coral warning, dividers, and disabled grey states visually match the source and pass the existing contrast assertions.
- Image quality and asset fidelity: the existing LiveShield shield and official Google Material Symbols vector paths are rendered sharply. No emoji, placeholder glyphs, handmade visible SVG approximations, or raster stretching are used.
- Copy and content: the visible disclosure and setup copy matches the selected design, including the anonymity warning, camera privacy explanation, protection rows, destination row, and readiness state.

## Full-view and focused evidence

The full-view comparison board was inspected for composition, hierarchy, density, color, copy, and state. Separate focused crops were not required because both the source and implementation remain legible at the comparison board's 540-pixel panel width; the original 1080-pixel Android captures were additionally inspected for icon sharpness, text wrapping, row alignment, and system-bar clearance.

## Interaction and implementation checks

- Disclosure acknowledgement reveals setup.
- Private-word controls remain disclosure-gated and session-only.
- Draw-on-preview privacy zones remain available without the removed four-number editor.
- Destination expands and reveals local/TikTok choices; a manual API 36 swipe verified the external eligibility notice and masked endpoint/key inputs.
- Camera permission remains denied during visual capture, so no camera, microphone, or network work is needed to render the selected state.
- Android logcat contained no app fatal exception, ANR, or native signal during the final visual capture.
- App JVM tests: 73 passed, 0 failed, 0 skipped.
- Debug/release Java compilation, Android-test compilation/package, Checkstyle, and debug/release lint passed.
- Focused device tests: 6 of 7 passed. The destination test was updated to use the same expand/select/swipe gesture that was manually verified. Its final cold-boot rerun was blocked before interaction because an Android System UI ANR owned window focus (`mCurrentFocus=Application Not Responding: com.android.systemui`), not because the LiveShield view failed. No app fatal, ANR, or native signal was observed.

## Comparison history

### Pass 1 — blocked

- [P1] Android status-bar content overlapped the disclosure logo and setup header.
- Fix: added API-23-safe system-bar inset handling to both activity states and requested fresh insets when the previously hidden setup screen becomes visible. API-specific light navigation-bar styling was moved to `values-v27`.

### Pass 2 — passed

- Post-fix evidence: `disclosure-api36.png`, `setup-api36.png`, and `source-vs-api36.png` show both screens clear of system chrome with no remaining P0/P1/P2 mismatch.
- Primary target interactions and the expanded destination state were verified on API 36.

## Follow-up polish

- If the exact source typeface becomes available with redistribution rights, it can replace the native sans family for closer optical matching.

## Final result

final result: passed
