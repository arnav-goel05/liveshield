# Consented evaluation-data deletion audit

**Audit date:** 2026-08-13  
**Task:** T116  
**Status:** VERIFIED ZERO-DENOMINATOR — no retained consented raw recording is represented by the repository inventory

## Result

The repository-safe inventory contains **zero authorized consented captures**, **zero retained
consented raw recordings**, **zero consented deletion deadlines**, and therefore **zero expired
deadlines requiring deletion** as of the audit date.

| Deletion population | Count | Repository evidence |
|---|---:|---|
| Manifest records | 274 | 216 licensed-public records and 58 synthetic records |
| `CONSENTED_CAPTURE` records | 0 | No manifest record has this `sourceKind` |
| `CONSENTED_FACE` records | 0 | No manifest record has this group |
| Populated `captureAuthorization` records | 0 | No manifest contains authorization/storage/access metadata |
| Retained consented raw recordings | 0 | No consented record or raw-media reference exists in the inventory |
| Consent-based deletion deadlines | 0 | No populated `deletionDeadline` exists |
| Deadlines expired by 2026-08-13 | 0 | Empty deadline population |
| Deletions due or performed in this audit | 0 | No asset exists to delete or falsely attest as deleted |

This is a zero-denominator result, not a deletion event and not evidence from a participant study.
No external ledger entry or deletion-audit record was created because doing so would invent an
asset, authorization, deadline, verifier, or deletion that does not exist.

## Inventory boundary

The audited repository inventory was:

- [`public-v1.jsonl`](../../test-fixtures/manifests/public-v1.jsonl): 200 WIDER FACE and 16
  BIV-Priv-Seg licensed-public still-image records;
- [`system-v1.jsonl`](../../test-fixtures/manifests/system-v1.jsonl): 12 renderer and 20
  fault-injection synthetic records; and
- [`pii-v1.jsonl`](../../test-fixtures/manifests/pii-v1.jsonl): 26 Priority 2 synthetic records.

The repository MP4 search found only the generated synthetic `system-v1` and `pii-v1` fixture
media. Those files are not participant recordings and are outside the consented-capture deletion
population. `evaluation-data/public/` contains licensed WIDER and BIV still-image evaluation data,
not consented raw recordings.

Schema definitions, validator fixtures, consent-form blanks, and fictional references inside unit
tests are controls or examples. They are not populated external-ledger evidence and were excluded
from the asset count.

## External-store evidence boundary

The approved process requires populated ledgers and deletion audits to remain in encrypted storage
outside Git, as specified by the
[`consented-capture protocol`](../testing/consented-capture-protocol.md),
[`ledger schema`](../testing/templates/consented-capture-ledger.schema.json), and
[`deletion-audit schema`](../testing/templates/deletion-audit.schema.json).

No external encrypted evaluation store, filled access ledger, signed authorization store, or
deletion-audit store was supplied to or accessed by this repository audit. Accordingly, this report
does not claim a storage audit, cryptographic erasure, consent verification, or deletion of an
off-repository asset. Its conclusion is bounded to the mechanically inspected repository inventory
and the existing protocol status, which states that capture is planning-only and not authorized.

If an external asset exists despite having no repository-safe manifest reference, this report does
not cover it. That discrepancy is a protocol violation: stop use, revoke access, inventory all
copies, and perform the external deletion audit before relying on this status.

## Mechanical verification

The manifest scan parsed every JSON/JSONL object under `test-fixtures/manifests/` and
`evaluation-data/`, then counted `sourceKind`, `group`, and `captureAuthorization`. Its relevant
result was:

```text
LICENSED_PUBLIC / WIDER_FACE: 200
LICENSED_PUBLIC / BIV_PRIV_SEG: 16
SYNTHETIC / RENDERER: 12
SYNTHETIC / FAULT_INJECTION: 20
SYNTHETIC / PRIORITY_2: 26
CONSENTED_CAPTURE: 0
CONSENTED_FACE: 0
captureAuthorization: 0
```

A separate repository media-extension scan, excluding `.git`, `.gradle`, and build directories,
found 58 MP4 files, all beneath the two generated synthetic fixture directories. It found no MOV,
MKV, WebM, AVI, or M4V file and no consented-media directory.

The search for populated authorization references, encrypted-storage references, authorized-access
references, deletion deadlines, and deletion-audit statuses in repository/evaluation manifests
returned no matches. Example values under validator tests were deliberately excluded because they
exercise schema rejection/acceptance and do not describe real assets.

## Re-audit triggers

This zero-denominator verification becomes stale immediately when any of the following occurs:

1. a device-validation go decision authorizes consented capture;
2. an opaque consented asset, authorization, storage, or access reference is created;
3. a `CONSENTED_CAPTURE` / `CONSENTED_FACE` manifest record is added or changed;
4. a capture is rejected, a participant withdraws, a protocol violation occurs, or an authorization
   expires;
5. a planned validation use completes, a deletion deadline changes, or any deadline becomes due;
6. an external inventory/ledger reconciliation finds an asset absent from the repository-safe
   manifest; or
7. a storage, backup, encryption, access, or synchronization incident is suspected.

At each trigger, reconcile the external ledger by opaque asset reference, calculate deadlines using
the protocol rule (the earlier of 30 days after final planned use or 90 days after capture unless a
shorter deadline applies), and audit primary media, device/staging copies, working copies, exports,
annotations, access, and controlled backups. Store the populated audit only in the approved
encrypted external store. Update this repository report with counts and status, never identities,
raw paths, credentials, recognized text, media, or signed forms.

## Verification status

- Repository consented-capture deletion denominator: **0**.
- Expired repository-represented deadlines: **0**.
- Outstanding repository-represented deletions: **0**.
- External-store audit: **not performed; no store or ledger was supplied**.
- Raw participant media deleted by this task: **none, because none was authorized or inventoried**.

