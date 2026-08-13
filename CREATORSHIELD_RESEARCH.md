# CreatorShield: Pain Points and Solution Research

Research reviewed through **12 August 2026**.

## Phase 1 focus: build this first

- **Target user:** a campus club, school-event volunteer, student journalist, or small creator preparing a 30–60 second public video.
- **Job to be done:** find and cover unintended people and visible personal information before sharing the clip.
- **Pain point 1 — unintended faces:** detect and track every face, cover all faces by default, and let the creator explicitly keep selected tracks visible for this export.
- **Pain point 2 — visible information leaks:** warn about email addresses, phone numbers, payment-card-like numbers, QR codes, parcel labels, name badges, and screens using on-device OCR, barcode scanning, and conservative pattern rules.
- **Pain point 3 — hidden automation failures:** show an uncertainty timeline for weak detections and broken tracks, preserve masks briefly across missed frames, and provide manual region correction.
- **Safe output:** render a new publish-safe MP4, preserve the original separately, scan the exported copy again, and share through the Android Sharesheet.
- **Deliberately excluded from Phase 1:** direct TikTok LIVE integration, facial recognition, long-term consent records, spoken-PII detection, generative face replacement, cloud processing, and automatic legal-compliance claims.
- **Primary success measure:** missed privacy leaks per minute on a staged, manually annotated video set; secondary measures are review time, false warnings, longest uncovered face gap, export time, and peak memory.

## Plain-language problem statement

- Small creators and community teams publish videos quickly, but their consent records, privacy checks, and video-editing tools are usually separate.
- CreatorShield would bring those checks into one on-device Android workflow before a video is posted.
- The product is a privacy aid requiring final human review. It must never claim to guarantee anonymity, consent, or legal compliance.

## Exact pain points

### Accidental personal-information leaks

- A livestream or recorded clip can reveal a home or school address, employer, phone number, email address, payment card, parcel label, QR code, name badge, or computer screen.
- These details may appear only briefly or in the background, making them easy for the creator to miss.
- TikTok specifically warns LIVE creators not to reveal their exact location or personal information accidentally in the background.
- The consequences can include stalking, phishing, fraud, identity theft, financial exploitation, or unwanted contact.

### LIVE cannot be corrected before viewers see it

- A recorded video can be reviewed and edited; a livestream is public immediately.
- Creators must entertain viewers, read comments, operate controls, and watch their surroundings at the same time.
- Ofcom's research recorded examples involving home addresses, bank cards, school information, unexpected interruptions, and unpredictable guests.
- Reacting to a problem can interrupt the stream, and the information may already have been seen or recorded.

### Bystanders and children may appear without suitable permission

- School, campus, NGO, community-event, and street-interview footage often contains people who were not the intended subject.
- Permission or opt-out information may exist in a form or spreadsheet, while the actual review happens separately in a video editor.
- The editor must remember who can appear, find that person throughout a moving video, and apply the correct rule for the intended channel.
- A face-blurring tool alone does not understand whether permission applies to public TikTok, an internal school group, or another use.

### Existing face blur is not enough

- Faces are only one source of identification.
- A person may still be identified through their voice, clothing, tattoo, uniform, name badge, vehicle plate, background landmark, address, device screen, or file metadata.
- Existing consumer editors commonly concentrate on manual blur or face tracking rather than a complete privacy preflight.
- Automated redaction can also fail on small faces, profiles, low light, motion blur, occlusion, or people crossing paths.

### Creators cannot easily see where automation is uncertain

- A detector can miss a face for several frames or lose its tracking identity after a cut or obstruction.
- A smooth-looking preview can hide these short failures.
- Users need a timeline that jumps to uncertain frames, missed tracking gaps, and possible sensitive text.
- When confidence is low, the safe default should remain redacted until a person reviews it.

### Manual review is slow and inconsistent

- Volunteers and small creators may need to scrub through an entire video several times.
- Moving redaction boxes require manual keyframes and can flicker or drift.
- Time pressure increases the chance that a leak will be missed.
- Discovering the mistake after publication may require takedowns, re-editing, re-uploading, and contacting affected people.

### Raw footage may be exposed during the privacy process

- Cloud-based processing requires the unredacted original to leave the device before it is protected.
- That is unsuitable for sensitive interviews, children, private homes, or confidential documents.
- On-device analysis reduces this exposure and allows an airplane-mode demonstration in which no footage is uploaded.

### Consent may change after a video project begins

- Someone may permit internal use but not public social-media use.
- Permission may expire or be withdrawn for future publication.
- Ordinary video editors do not connect a person's track to a purpose-scoped consent record.
- Once a video has been downloaded or reposted, the application cannot promise to recall every copy; it can only flag controlled projects and future exports.

### The original and the publish-safe copy serve different purposes

- Journalistic or safeguarding footage may need an untouched original as evidence.
- Editing or deleting that original could damage provenance.
- The application should preserve the original separately and create a new redacted, metadata-sanitized publish copy.

### Mobile performance affects safety

- Face detection, OCR, video preview, and export compete for CPU, GPU, memory, battery, and thermal headroom.
- If analysis falls behind, frames may be skipped and a privacy leak may go undetected.
- The application needs visible degraded-performance warnings, conservative fallbacks, and measured latency rather than an unsupported claim of real-time protection.

### Platform integration has practical limits

- TikTok offers a Content Posting API for completed videos, but public posting requires approval and unaudited clients are restricted to private test posting.
- There is no generally available TikTok API for replacing the mobile LIVE camera feed or controlling LIVE moderation.
- The credible first product is therefore a pre-post privacy check and a LIVE rehearsal, not a claim of direct TikTok LIVE interception.

## Most practical initial users

- Campus clubs and student societies producing event recaps.
- School or youth-group communications volunteers handling opt-outs.
- Student journalists and street interviewers protecting sources and bystanders.
- NGOs and community groups publishing public stories.
- Small creators filming at home near documents, screens, parcels, or family members.
- Parents creating shareable versions of event videos containing other children.

## Sources supporting the pain points

- [TikTok LIVE Safety Guide](https://www.tiktok.com/safety/en/live-safety-guide?sc_version=2024)
- [TikTok privacy and security Community Guidelines](https://www.tiktok.com/community-guidelines/en/privacy-security/)
- [Ofcom: children and young people who create livestreams](https://www.ofcom.org.uk/siteassets/resources/documents/online-safety/research-statistics-and-data/protecting-children/research-among-children-and-young-people-who-create-livestreams.pdf?v=418213)
- [Ofcom: children and young people who view livestreams](https://www.ofcom.org.uk/siteassets/resources/documents/online-safety/research-statistics-and-data/protecting-children/research-among-children-and-young-people-who-view-livestreams.pdf?v=418212)
- [UK ICO guidance on school photos and videos](https://ico.org.uk/for-the-public/schools/photos)
- [Singapore PDPC guidance on photos and video recordings](https://www.pdpc.gov.sg/guidelines-and-consultation/2020/02/advisory-guidelines-on-the-personal-data-protection-act-for-selected-topics)
- [Committee to Protect Journalists: protecting confidential sources](https://cpj.org/2021/11/digital-physical-safety-protecting-confidential-sources/)
- [UNICEF ethical reporting guidance](https://www.unicef.org/oman/stories/ethical-reporting-guidelines)
- [TikTok Content Posting API guidelines](https://developers.tiktok.com/doc/content-sharing-guidelines/)

## Emerging-solution comparison

The table separates ideas that can credibly be built now from promising research that remains too heavy, immature, or insufficiently tested for a mobile safety product.

| Approach and source | Proposed solution | Evidence or reported result | Main limitation | Java Android fit | CreatorShield decision |
|---|---|---|---|---|---|
| **FPVLS live face tracking** — [IEEE TIFS 2021](https://arxiv.org/abs/2101.01060) | Select the main streamer, associate faces across frames, refine broken trajectories, and pixelate everyone else after a short buffer. | Evaluated on the authors' collected livestream dataset and reported real-time operation; the paper's 30-frame segment corresponds to roughly a two-second buffer. | Uses older, relatively heavy face recognition and biometric embeddings; dataset and maintained code are not clearly public. | **High if simplified.** Use ML Kit detection and non-biometric track association instead of copying the recognition pipeline. | **Adopt the interaction:** tap to allow a host, redact all other tracks, and carry masks across brief misses. |
| **FaceWard selective mobile anonymization** — [MobileHCI 2023](https://orbilu.uni.lu/handle/10993/60611) | Preserve a selected target while blurring or covering other people in a mobile photo. | Open-source, mobile-oriented prototype that avoids training a large custom model. | Photo-focused and supported by prototype-scale evidence rather than a robust video privacy evaluation. | **High.** The selection interaction is straightforward on Android. | **Adopt the UX**, but require manual review because selection or matching errors could expose someone. |
| **EgoBlur** — [paper](https://arxiv.org/abs/2308.13093), [official tools](https://facebookresearch.github.io/projectaria_tools/docs/open_models/egoblur) | Detect and blur faces and licence plates in egocentric video before release. | Open models and a real dataset-production workflow; evaluates difficult egocentric footage and demographic slices. | The published models are around 400 MB each and unsuitable for live phone inference; only faces and plates are covered. | **Low live; medium offline** unless replaced by smaller mobile detectors. | **Adopt its evaluation discipline:** test tiny, partial, blurred, angled faces and report subgroup performance. |
| **LA3D adaptive anonymization** — [IEEE TIFS 2026 / preprint](https://arxiv.org/abs/2410.18717) | Adjust masking, pixelation, or blur strength according to target size or depth rather than using one fixed effect. | Cross-dataset evaluation reports improved privacy without severely damaging anomaly-detection utility; [code is available](https://github.com/muleina/LA3D). | Designed for surveillance and crowd analytics, not creator video; mobile performance is not established. | **Medium to high** when reduced to adaptive pixelation over mobile detections. | **Adopt:** enlarge and strengthen masks for large, close, or uncertain targets. |
| **DeepPrivacy2 full-body anonymization** — [WACV 2023](https://arxiv.org/abs/2211.09454), [code](https://github.com/hukkelas/deep_privacy2) | Replace faces or complete bodies with generated synthetic people so clothing and body cues are also hidden. | Reports much lower full-body re-identification performance than unmodified footage while retaining useful pose/detection information. | Computationally expensive, image-oriented, susceptible to artifacts and detector failures, and not temporally reliable enough for a mobile MVP. | **Low on-device.** | **Research stretch only.** It demonstrates why face-only protection is incomplete. |
| **FIVA stable synthetic identities** — [ICCV Workshop 2023](https://openaccess.thecvf.com/content/ICCV2023W/DFAD/html/Rosberg_FIVA_Facial_Image_and_Video_Anonymization_and_Anonymization_Defense_ICCVW_2023_paper.html) | Replace a real face with one consistent artificial identity across a video and defend against reconstruction attacks. | Reports zero true positives at a false-acceptance rate of 0.001 in its FaceForensics++ evaluation and examines reconstruction attacks. | GPU-heavy, no official maintained implementation was verified, narrow benchmark, and synthetic output may mislead viewers or create new trust problems. | **Low.** | **Do not use in the MVP.** Later compare it with obvious masks in privacy and user-trust testing. |
| **PrivObfNet sensitive-region segmentation** — [WACV 2024](https://openaccess.thecvf.com/content/WACV2024/html/Tay_PrivObfNet_A_Weakly_Supervised_Semantic_Segmentation_Model_for_Data_Protection_WACV_2024_paper.html) | Predict an image privacy score, identify sensitive attributes, and localize regions that should be obscured. | On VISPR, reports Pearson 0.88 and Spearman 0.86 for privacy scoring; its weakly supervised masks perform better for common human attributes than sparse text. | Privacy is contextual and subjective; the model performs poorly on some documents, vehicle ownership, internet activity, and other less-represented content. | **Medium offline; uncertain live.** | **Use as future research**, not as the sole safety detector. Keep explicit OCR and rules for important leak types. |
| **VPD-100K visual privacy detection** — [ICML 2026 project/preprint](https://vpd-100k.github.io/) | Detect 33 LIVE-relevant classes covering people, on-screen PII, identity/payment documents, and location indicators. | Dataset contains 100,000 images and over 190,000 instances. The proposed model reports F1 0.81 and AP50 73.4; live-stream AP50 is 72.8. | New research; linked repository currently has very little implementation content, and reported 7.51 ms latency is not established on a phone. | **Medium later; high as a taxonomy and benchmark source.** | **Adopt its risk taxonomy now.** Use a carefully licensed subset to test CreatorShield and avoid claiming its model is phone-ready. |
| **BIV-Priv-HIT hierarchical tracking** — [WACV 2026 preprint](https://arxiv.org/abs/2512.10102) | Track both an entire sensitive object and its sensitive subparts—for example, hide a patient's name but retain useful dosage text. | Dataset covers 552 videos, 2,765 tracked entities, and 40 object/part categories. Fine-tuned SAM 2 reportedly improves hierarchical tracking MOTA from about 0.39 to 0.72. | Models still struggle with tiny parts and identity switches; SAM 2 requires GPU-scale resources and often a first-frame mask. | **Low as a model; high as a product pattern.** | **Adopt the idea:** mask only sensitive fields, allow manual seeding, and propagate corrections through time. |
| **VisShield configurable private-text detection** — [ACL Findings 2025](https://aclanthology.org/2025.findings-acl.236.pdf) | Let a user define what counts as private, then locate and mask matching text in an image. | Reports high F1 and IoU on many synthetic PII categories and substantially outperforms an OCR-plus-language-model baseline in its tests. | Much of the evidence is synthetic and training/evaluation used an A100 GPU; it is not a mobile model. | **Low on-device.** | **Adopt the configurable-policy idea:** “hide school names and addresses, keep product prices.” Consider a distilled model later. |
| **OCR plus PII/entity detection** — [video-text paper](https://arxiv.org/abs/2208.10270), [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [entity extraction](https://developers.google.com/ml-kit/language/entity-extraction) | Read text from frames, classify phone numbers, emails, cards, addresses, tracking numbers, and other PII, then redact the corresponding boxes. | Research prototypes validate the OCR-plus-NLP pattern. ML Kit supplies Java-ready on-device bounding boxes and entity types. | OCR depends on focus, size, lighting, font, rotation, and language. Entity Extraction prioritizes precision and deliberately misses some cases. | **High for the MVP.** | **Build now:** OCR plus deterministic validators, conservative warnings, temporal box tracking, and manual confirmation. |
| **Multimodal visual and spoken PII filtering** — [IEEE Access 2026](https://doi.org/10.1109/ACCESS.2026.3670925) | Detect visual labels/name tags/address blocks and transcribe speech, classify PII, then black out video regions and silence matching audio intervals. | Reports visual false negatives of 5.91% and very high positive-class audio recall in its own tests. It also carries a visual box forward during short missed detections. | Uses YOLO, Whisper Large, and XLM-RoBERTa; the combined stack is not credible for reliable live phone operation and public code was not verified. | **Low live; medium offline with smaller substitutes.** | **Later module:** use timestamped bleep/mute rather than risky voice cloning. Adopt fail-closed carry-forward now. |
| **BystandAR on-device subject/bystander inference** — [ACM MobiSys 2023](https://vtechworks.lib.vt.edu/items/1a81ea7d-59ca-499f-b656-e844df99b35f) | Combine gaze, voice, and spatial awareness to distinguish intended subjects from incidental bystanders entirely on-device. | A 16-person study reports protecting 98.14% of bystanders while retaining 96.27% of subjects at an average 52.6 FPS on its AR platform. | Depends on AR gaze, depth, and wearer-focused audio sensors not normally available to a phone creator workflow. | **Low directly; useful conceptually.** | **Use the principle:** infer intent from multiple cues but never reveal a person automatically without confirmation. |
| **Privacy by default with consent restoration** — [CHI 2026, “See Me If You Can”](https://doi.org/10.1145/3772318.3790394) | Blur everyone locally by default, optionally replace faces, and restore a person's original appearance only after consent. | Working camera-glasses protocol and interviews with 18 wearers/bystanders; bystanders strongly valued opt-in default protection. | Small qualitative study in a glasses context; face embeddings, restoration infrastructure, and synthetic replacement add security and trust risks. | **High as policy/UX, low as its complete protocol.** | **Core design rule:** unknown tracks stay hidden; reveal requires an explicit, purpose-scoped decision. |
| **Dynamic consent** — [SOUPS 2025 poster](https://www.usenix.org/conference/soups2025/presentation/golap-poster) | Reconfirm consent when framing, context, audience, or the people visible in the frame change. | A 50-person study around adaptive camera framing found static consent insufficient and participants preferred real-time notice and control. | Poster-level evidence and a video-call scenario rather than a deployed short-video tool. | **High as application logic.** | **Adopt:** mark consent stale when a track reappears uncertainly, the crop changes, or the export destination changes. |
| **Reciprocal LIVE informing** — [SOUPS 2024](https://www.usenix.org/conference/soups2024/presentation/wu) | Tell bystanders not just that recording is happening but also the platform, expected audience, subject, devices, and capture footprint; alert streamers discreetly when risk changes. | Co-design work with 21 streamers and bystanders found both groups wanted better reciprocal information and platform-assisted alerts. | Qualitative design evidence, not an effectiveness trial. | **High for rehearsal UX.** | **Adopt:** an audience/destination card, visible capture boundary, haptic alerts, and an optional QR privacy notice. |
| **SelfFlag bystander signal** — [CHI 2025](https://www.chenhuangxun.com/files/chi25-selfflag.pdf) | A bystander plays recognizable copyrighted music so a platform flag can trigger later removal of that person and the audio. | Small experiments ranged from 100% successful editing in a restaurant to 80% in an outdoor corridor, with a 70-person preference survey. | Brittle dependence on copyright enforcement, cloud processing, social disturbance, overlapping signals, and platform policy. | **Low as proposed.** | **Do not copy the music mechanism.** Take the validated need for discreet opt-out signals and creator-side cleanup. |
| **Human–AI uncertainty review** — [ImageAlly, SOUPS 2023](https://www.usenix.org/conference/soups2023/presentation/zhang), [Ego4D workflow](https://openaccess.thecvf.com/content/CVPR2022/html/Grauman_Ego4D_Around_the_World_in_3000_Hours_of_Egocentric_Video_CVPR_2022_paper.html) | Automation proposes risks; people review false positives, misses, and ambiguous content using a focused interface rather than trusting full automation. | ImageAlly users reported greater control; Ego4D's operational pipeline explicitly combines automated face/plate detection with human correction and reports redaction work taking roughly 1.5–10 times video duration. | Human review costs time and can still make mistakes. | **Very high.** | **Core MVP:** group detections into tracks, show uncertain intervals first, propagate one correction, and block “reviewed” status until flags are acknowledged. |
| **Pre-post privacy nudges** — [field-trial paper](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4234567), [systematic review](https://pmc.ncbi.nlm.nih.gov/articles/PMC8396794/) | Show concrete privacy feedback, audience cues, or a posting delay immediately before sharing. | A three-week exploratory trial used 21 participants; the larger review found an overall small-to-medium disclosure effect but substantial heterogeneity. | Generic warnings can be ignored, fatigue users, or even backfire; most studies are not video-specific. | **High if findings are specific.** | **Adopt a short, evidence-based preflight:** show exact unresolved risks and destination, not a generic warning dialog. |
| **Accessible camera privacy notices** — [USENIX Security 2023](https://www.usenix.org/conference/usenixsecurity23/presentation/zhao-yuhang) | Make recording and privacy notices perceivable through audio, haptics, or proximity—not only a visible light or screen icon. | Surveyed 90 blind/visually impaired and 96 sighted respondents, followed by 16 in-depth interviews with visually impaired bystanders. | Needs study rather than an evaluated implementation. | **High for interface design.** | **Adopt:** rehearsal and recording state should have optional audible and haptic notice. |

## Recommended synthesis for the project

### Build in the MVP

- Use ML Kit face detection, OCR, and barcode scanning because they are Java-ready and can operate locally.
- Blur or cover all unknown face tracks by default.
- Let the user explicitly reveal only tracks that are permitted for the selected destination.
- Carry the last safe mask across short detection gaps and enlarge it when confidence falls.
- Detect high-confidence text patterns such as emails, phone numbers, card numbers, URLs, QR codes, and tracking numbers.
- Provide a timeline of uncertain frames and unresolved findings rather than presenting automation as final.
- Support manual boxes for screens, tattoos, uniforms, plates, or other risks automation cannot understand.
- Create a separate publish-safe copy; preserve the original separately.
- Fully render privacy-sensitive exports and verify the exported file instead of assuming preview overlays or metadata removal succeeded.
- Show the intended platform and audience in the final preflight.

### Research-informed features that can make it stand out

- Purpose-scoped consent: internal school sharing and public TikTok are separate decisions.
- Fine-grained document masking: hide a name or address without destroying all useful text.
- Context profiles such as home, school event, public interview, and NGO story.
- LIVE rehearsal that warns about newly visible faces, text, cards, screens, or QR codes without broadcasting.
- Accessible recording notices through screen, sound, and haptics.
- A privacy-strength explanation distinguishing soft blur from irreversible opaque or mosaic redaction.
- Post-export re-scan to check whether any protected information remains visible.

### Keep experimental

- Synthetic face or body replacement.
- Automatic facial recognition of consenting people.
- Spoken-PII detection during live video.
- A delayed livestream that automatically pauses or covers the entire frame.
- Platform-driven bystander opt-out signals.

These ideas are promising, but current papers do not establish that they are reliable, fair, secure, and performant enough for the core Android safety claim.

## Strongest evaluation plan

- **Primary safety metric:** missed privacy leaks per minute of video.
- Missed-face frames per 1,000 annotated face frames.
- Longest interval in which a face should be covered but is not.
- Sensitive-region coverage measured against annotated boxes or masks.
- False warnings per minute.
- Track identity switches and redaction flicker.
- Results split by face size, pose, lighting, motion blur, occlusion, and relevant demographic slices.
- p50 and p95 analysis latency on at least one low/mid-range and one high-end Android device.
- Preview frame rate, peak memory, battery drain, and device temperature.
- Export duration divided by video duration.
- Re-identification testing after social-media-like resizing and recompression.
- User study comparing normal manual editing with CreatorShield on the same staged, consented clips.

## Final research conclusion

- No reviewed paper supplies a complete, proven mobile solution for creator-video consent, visual PII, bystander privacy, LIVE risk, and safe export.
- The opportunity is therefore in **integrating mature mobile primitives with research-backed safety interaction**, not inventing one enormous model.
- The most defensible product is a privacy-by-default preflight with dynamic consent, multi-signal detection, a human uncertainty queue, and verified export.
- Automatic LIVE delay or pausing remains a promising experiment; no strong controlled trial was found showing that it reliably prevents accidental PII disclosure in creator livestreams.
