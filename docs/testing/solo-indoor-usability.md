# Solo-indoor usability study preregistration

**Status:** protocol frozen before recruitment; no participant sessions have been run  
**Planned sample:** at least 10 completed first-time-user sessions  
**Scope:** LiveShield V1 in one controlled indoor room using the controlled demonstration
destination; an eligible TikTok account is not required  
**Evidence boundary:** usability and comprehension only, not detector accuracy, encoded-output
safety, TikTok compatibility, anonymity, or physical-device performance

## Purpose and fixed success criteria

This study tests whether a representative first-time creator can complete the bounded workflow
without specialist assistance and can understand LiveShield's private health states. Recruitment,
instructions, task order, scoring, and exclusions are fixed by this document before data collection.

The preregistered criteria are:

- at least 9 of 10 completed participants independently select a host, confirm readiness, begin a
  protected session, recognize the induced shielding event, and end safely;
- at least 9 of 10 completed participants correctly distinguish healthy, degraded, and shielding;
- at least 9 of 10 participants with valid controlled-viewer test configuration independently
  configure the destination, start the protected broadcast, recognize a connection failure, and
  end safely; and
- report time from feature opening to the first protection-ready state, including how many
  participants reach it within 30 seconds. This is reported as a distribution and exact count, not
  as a pass inferred from an average.

Every result will use the actual numerator and denominator. A participant failure, timeout, or
assisted completion is retained and cannot be replaced to improve a rate.

## Participant eligibility

A participant is eligible only if all of the following are confirmed before the task begins:

- age 18 or older and able to give free, informed consent;
- has not previously used, tested, designed, implemented, or received a walkthrough of LiveShield;
- is not on the LiveShield project team and has not seen this task script or answer key;
- has used a smartphone camera or live/video-call application at least once, so the sample tests a
  first-time LiveShield workflow rather than basic smartphone literacy;
- can read the study language and operate the supplied phone using their usual corrected vision or
  accessibility aids; and
- self-reports plausible interest in creating or sharing live/video content, or experience with a
  comparable creator workflow. Existing TikTok or external-encoder eligibility is not required.

Recruit across differing levels of live-stream familiarity where practical. Record only coarse,
optional categories such as `none`, `occasional`, or `frequent`; do not retain account names,
handles, contacts, demographics not needed for analysis, or free-form biographical notes.

## Consent, safety, and privacy boundary

Before participation, explain the purpose, fixed indoor scope, requested interactions, what is
recorded, withdrawal route, and that declining has no penalty. Participation is voluntary and may
be paused, skipped, or stopped without a reason. Do not recruit through a dependent relationship
that could make refusal difficult.

Use a study phone, a controlled indoor room, generated face/health fixtures or consenting study
staff, fictional destination details, and synthetic non-sensitive props only. No incidental person,
real personal information, stream secret, real social account, or participant account may enter the
flow. V1 remains video-only; microphone permission and audio recording must be absent.

Do not record participant camera video, screen video, audio, facial imagery, recognized text,
biometrics, tap coordinates, or destination values. The retained record uses a random study ID and
only task outcomes, assistance codes, timestamps/durations, fixed-choice comprehension responses,
withdrawal/missing status, and optional short operator codes from the controlled vocabulary below.
Store the de-identified study data only under the gitignored `evaluation-data/usability/` location;
do not paste row-level results into Git, chat, issue trackers, screenshots, logs, or analytics.

## Study setup

Use the same phone model, build, display settings, room, network, controlled-viewer endpoint, and
prepared fault script for all participants unless a documented safety or accessibility need
requires a change. Record the build identifier, phone model, Android version, display orientation,
network condition, and test date once per session without device account identifiers or location.

Before each participant:

1. Reset LiveShield application data and session state.
2. Confirm camera permission can be requested and microphone permission is absent.
3. Confirm the room contains no real private material and only approved people/fixtures are visible.
4. Confirm the controlled destination works, then return the app and viewer to their initial state.
5. Prepare a deterministic destination-failure switch that changes only network/destination state
   and cannot expose an untreated camera path.
6. Start an operator monotonic clock synchronized to the event definitions below.

The operator reads instructions verbatim and does not point at controls, paraphrase health labels,
confirm guesses, or demonstrate a task before the participant attempts it.

## Fixed participant briefing

Read:

> You are testing a first version intended for one creator in a controlled indoor space. It
> visually protects video but does not guarantee anonymity and does not capture microphone audio.
> Please think aloud if comfortable, but I cannot guide you through the controls. You may pause,
> skip, or stop at any time. Complete the tasks as you naturally would. Public venues, outdoor
> mobile streaming, and dense crowds are not validated uses for this version.

Do not describe what healthy, degraded, or shielding means before the comprehension questions.

## Fixed task flow and scoring

Run tasks in this order. The operator records start/end events and one outcome code immediately
after each task.

| Step | Participant task | Start event | Independent success event | Failure/timeout rule |
|---|---|---|---|---|
| 1 | Open LiveShield and progress through camera permission | Feature first visible | Sanitized setup preview and host-selection instruction visible | No progress for 60 seconds or assistance |
| 2 | Select the one intended host | Host-selection instruction visible | App accepts an explicit fresh host tap; no other track is selected | Wrong/stale/ambiguous tap not corrected independently within 60 seconds |
| 3 | Decide when protection is ready | Host accepted | Participant states readiness and the app is actually `READY` | Claims ready before `READY`, or no decision within 60 seconds |
| 4 | Configure the supplied controlled destination and begin protected output | Destination task card presented | Controlled viewer receives sanitized video and app enters the active state | Incorrect configuration, unsafe attempt, assistance, or 120 seconds |
| 5 | Respond to a pre-scripted degraded state | Degraded state becomes visible | Participant says protection is degraded/uncertain and continues to monitor without claiming normal operation | Misclassifies it as healthy/shielding or no answer within 30 seconds |
| 6 | Respond to a pre-scripted full shielding event and host-continuity loss | Shielding state becomes visible | Participant says camera imagery is hidden/protected, recognizes host selection is no longer valid, and explicitly selects a fresh host when prompted | Claims raw/normal video is visible, expects automatic identity transfer, or needs assistance |
| 7 | Respond to a destination connection failure | Failure is induced after a stable active interval | Participant recognizes publication/connection failure and does not claim viewers still receive normal output | Misclassifies it, searches for an untreated bypass, or no answer within 30 seconds |
| 8 | End the protected session | Operator says "Please end the session" after recovery or failed destination state | Participant activates the product's end control and app reports stopped/no publication | App remains active after 60 seconds or assistance |

If the product itself makes a downstream step impossible, record that step as `BLOCKED_PRODUCT`,
not participant success. Continue only where the participant can do so safely without changing the
script or concealing the product failure.

## Timing definitions

Use monotonic time with millisecond resolution. Do not pause the clock for participant hesitation,
ordinary loading, rejected taps, or product delay.

- `feature_to_ready_ms`: feature first visible to first actual `READY` state after explicit host
  selection. If never ready, record blank plus `NOT_REACHED`; do not enter a fabricated timeout.
- `destination_start_ms`: destination task card presented to confirmed controlled-viewer output.
- `safe_end_ms`: end instruction to stopped/no-publication state.
- per-task duration: the fixed start event to success, timeout, withdrawal, or product block.

Report median, minimum, maximum, and individual non-identifying durations for completed timing
observations. With this small sample, do not report only a mean or infer population performance.

## Assistance and observer conduct

`ASSISTED` means any task-specific hint, pointing, control name, correction, demonstration,
navigation instruction, interpretation of a status label, or answer to a comprehension item. A
clarification that only repeats the exact scripted instruction is `REPEAT_ONLY` and may occur once
per task; a second repeat is assistance. Accessibility accommodation agreed before timing is not
assistance when it does not reveal the task solution; record `ACCESS_ACCOMMODATION`.

If a participant asks for help, first say: "Please do what you think is safest." If they cannot
continue, give the minimum safe assistance, mark that task and the relevant end-to-end criterion as
assisted failure, and retain the participant in the denominator. Never coach a participant merely
to complete the script.

## Comprehension questions and answer key

After the task flow, show the three fixed status cards in a counterbalanced order. Ask each question
without feedback; record the selected answer before continuing.

1. **Healthy:** "If the private status says protection is healthy, what does that mean?"
   - A. The protected video path is operating normally, but I should keep monitoring and anonymity
     is not guaranteed. **Correct**
   - B. Every person and private item is guaranteed anonymous.
   - C. The camera is hidden behind a full-frame shield.
2. **Degraded:** "If protection is degraded, what should you expect?"
   - A. Protection may need to become stronger or switch to a full shield; I should monitor it.
     **Correct**
   - B. Raw video is allowed so the stream does not pause.
   - C. The session is permanently healthy again.
3. **Shielding:** "If the full privacy shield is active, what are viewers receiving?"
   - A. Camera imagery with a warning icon.
   - B. Camera imagery is not being shown; viewers receive the privacy shield while safe recovery is
     checked. **Correct**
   - C. A guarantee that nobody can identify the creator.
4. **Host continuity:** "After the app says host tracking became uncertain, what makes a face
   visible as host again?"
   - A. The app recognizes the same person's appearance automatically.
   - B. The creator explicitly taps one fresh face again. **Correct**
   - C. The closest face automatically inherits permission.
5. **Scope:** "Which use is within the evaluated V1 scope?"
   - A. One creator in a controlled indoor room. **Correct**
   - B. A moving outdoor stream.
   - C. A dense public crowd.
6. **Audio:** "What microphone audio does this V1 broadcast?"
   - A. None; this V1 is intentionally video-only. **Correct**
   - B. System-selected background audio.
   - C. Audio whenever another app is using the microphone.

The SC-005 comprehension outcome is successful only when questions 1–3 are all correct without
assistance. Report questions 4–6 separately as host-continuity and scope/disclosure comprehension;
do not silently substitute them into the preregistered three-state criterion.

## Outcome codes

Use only: `PASS_UNASSISTED`, `FAIL_INCORRECT`, `FAIL_TIMEOUT`, `ASSISTED`, `BLOCKED_PRODUCT`,
`WITHDRAWN`, `MISSING_TECHNICAL`, `REPEAT_ONLY`, and `ACCESS_ACCOMMODATION`. A technical loss is
`MISSING_TECHNICAL` only when no participant behavior and no product behavior produced the missing
record, such as an operator clock failure; app crashes and destination failures are product
outcomes, not missing data.

## Exclusion, withdrawal, and no-replacement rules

Pre-task exclusions are limited to: ineligible age/consent, prior LiveShield exposure, project-team
membership, inability to use the study language with the available accessibility setup, or a room/
device condition that violates the privacy boundary before the participant begins. Report the
count and reason category of pre-task exclusions, but they are not part of the enrolled denominator.

After Step 1 starts, the participant is enrolled. Do not exclude an enrolled participant for
errors, slowness, misunderstanding, assistance, app failure, destination failure, protocol-visible
edge cases, or an unfavorable result. Do not recruit a replacement for an enrolled failure,
timeout, assisted session, technical product failure, or completed partial record.

A participant who withdraws is not pressured to continue. Stop collection immediately and delete
their row if they request deletion. Otherwise retain only the minimal de-identified withdrawal
status and completed aggregate-eligible fields covered by consent. Report withdrawal counts and
the number of observations removed from each denominator. Recruitment may continue until at least
10 participants complete or partially complete the scripted flow, but every enrolled record is
reported; additional enrollment never replaces earlier failures.

## Preregistered analysis and exact reporting

Publish these counts in `docs/verification/us5-usability.md` after the separately authorized T071
run. This document does not claim those results now.

For each criterion report `numerator / denominator (percentage)`:

- **SC-004 complete indoor flow:** numerator = enrolled participants who independently pass Steps
  2, 3, 4, 6, and 8; denominator = all enrolled participants with outcome retained, including
  assisted, timeout, product-blocked, and participant failures. Report withdrawals separately and
  show the rate both excluding withdrawn observations with no usable outcome and conservatively
  counting them as not successful.
- **SC-005 three-state comprehension:** numerator = participants answering questions 1–3 all
  correctly without assistance; denominator = all enrolled participants asked at least one
  comprehension question. Also report per-question exact counts and participants not asked.
- **SC-010 destination flow:** numerator = participants who independently pass Steps 4, 7, and 8;
  denominator = all enrolled participants for whom the controlled destination was valid at Step 4
  start. Invalid operator configuration is reported as `MISSING_TECHNICAL` and never converted to
  participant failure or silently removed; provide an additional conservative all-enrolled rate.
- **SC-006 readiness timing:** numerator = participants whose `feature_to_ready_ms` is at most
  30,000; denominator = all enrolled participants who began Step 1. Report `NOT_REACHED`, assisted,
  blocked, and missing clock observations explicitly.

Also report:

- a participant-flow table: approached, pre-task excluded by category, consented/enrolled,
  completed, partial, withdrawn, assisted, and retained for each denominator;
- every task's exact outcome-code counts;
- timing observation counts plus median/min/max, with no imputation for missing times;
- assistance and repeat counts by task;
- the number and reason category of product blocks, app failures, destination failures, operator
  errors, missing fields, and protocol deviations; and
- all deviations from this preregistration, dated and explained before interpreting results.

Do not pool missing cases into successes, round `8/9` to "9/10", use the recruited count when fewer
participants received a question, or claim the criteria passed from an unqualified percentage.

## Stop and review conditions

Stop the study and review before another participant if raw/participant media is retained, audio is
captured, a real credential or account is exposed, an incidental person or real private item enters
the scene, an untreated output path is suspected, consent is uncertain, or the fixed fault control
cannot remain fail-private. A stopped study keeps prior valid and failed records; it does not reset
the sample.

## Authorization boundary

Creating or approving this preregistration does not authorize recruitment, participant contact,
account access, recording, or T071 execution. T071 remains a separate user-action task. Any protocol
change after recruitment begins must be versioned as a deviation and cannot rewrite prior outcomes.
