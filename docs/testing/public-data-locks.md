# Public Evaluation Data Locks

This record freezes the exact public artifacts used to prepare LiveShield corpus `public-v1` on
2026-08-13. Media remains under gitignored `evaluation-data/public/` and is not redistributed,
committed, or packaged in the application.

| Artifact | Official HTTPS source | Bytes | SHA-256 | Lock provenance |
|---|---|---:|---|---|
| WIDER FACE validation images | `https://huggingface.co/datasets/CUHK-CSE/wider_face/resolve/833d07e7bf3860f294242312fe95eed0561eeb17/data/WIDER_val.zip` | 362752168 | `f9efbd09f28c5d2d884be8c0eaef3967158c866a593fc36ab0413e4b2a58a17a` | Published by the revision-pinned Hugging Face repository and verified after download |
| WIDER FACE split/box annotations | `https://huggingface.co/datasets/CUHK-CSE/wider_face/resolve/833d07e7bf3860f294242312fe95eed0561eeb17/data/wider_face_split.zip` | 3591642 | `c7561e4f5e7a118c249e0a5c5c902b0de90bbf120d7da9fa28d99041f68a8a5c` | Published by the revision-pinned Hugging Face repository and verified after download |
| BIV-Priv-Seg support images | `https://vizwiz.cs.colorado.edu/biv-priv/images/support_images.zip` | 15732014 | `3a37b93daad15905fb2ffc25d76cccaa9d88c57d5fc23e2f5ac66dac7d3b3e2f` | Local content lock established from the official HTTPS retrieval and verified before preparation |
| BIV-Priv-Seg support annotations | `https://vizwiz.cs.colorado.edu/biv-priv/images/support_set.json` | 11220 | `3936b12169813da19659a8099484c13fd1692412659244444e0458425589476d` | Local content lock established from the official HTTPS retrieval and verified before preparation |

## BIV lock limitation

The BIV server supplied exact `Content-Length`, `Last-Modified`, and `ETag` response metadata but
did not publish a cryptographic digest. The two BIV SHA-256 values above therefore prove that local
preparation consistently uses the bytes first retrieved from the official HTTPS endpoints; they do
not independently prove a publisher-authored checksum. A later official artifact with different
bytes must be reviewed and recorded as a corpus-version change rather than silently accepted.

## Preparation boundary

- WIDER FACE is CC BY-NC-ND 4.0. The selector reads official annotations and extracts 200 selected
  JPEG members byte-for-byte. It does not crop, resize, recompress, augment, or redistribute them.
- BIV-Priv-Seg is CC BY 4.0. All 16 support images are extracted byte-for-byte, and the generated
  attribution is retained in `test-fixtures/manifests/BIV_PRIV_SEG_ATTRIBUTION.md`.
- The large archives were not fully extracted. Only the selected 216 images were materialized.
  After validation, the archives were removed during critical disk-pressure cleanup; the exact
  sources, sizes, and digests above make them reproducibly retrievable without changing corpus
  `public-v1`.
- `public-v1.jsonl` and its frame-truth files contain identifiers, hashes, geometry, licensing, and
  provenance only. They contain no image bytes, OCR strings, real PII values, or participant data.
- BIV truth uses the evaluation-only `BIV_PRIVATE_OBJECT` category and retains the official safe
  class label in `scenarioIds`. The validator permits that category only for licensed-public
  `BIV_PRIV_SEG` fixtures, so it cannot be mistaken for a supported V1 automatic detector class.
- Public corpus results are detector regression/smoke evidence only; they do not demonstrate live
  tracking, fail-private behavior, encoded-output safety, device performance, or consent.
