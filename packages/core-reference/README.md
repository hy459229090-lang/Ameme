# Ameme local core reference/oracle

> Status: **non-production reference/oracle only**. This package validates the MVP data model and closed-loop semantics with synthetic data. It is not the iOS/Android runtime and must not be presented as production storage.

## What it proves

The Python 3.12 standard-library implementation uses SQLite to execute this chain:

```text
AcquisitionContract
  -> SourceObject + optional UserAddendum
  -> Observation
  -> EventCandidate
  -> append-only EventRevision / current Event projection
  -> append-only EpisodeRevision / current Episode projection
  -> DayLedger Today view
  -> date/FTS Recall with day + field evidence
  -> source tombstone/deletion projection
  -> rebuild current/DayLedger/FTS projections from revisions
```

The Oracle enforces:

- `BEGIN IMMEDIATE` write transactions, foreign keys, WAL and `synchronous=FULL`;
- command idempotency: same key + same payload replays the stored result, while changed payload raises `IDEMPOTENCY_CONFLICT`;
- append-only Event/Episode revision tables protected by SQLite update/delete triggers;
- optimistic `base_revision` checks and explicit conflict revisions instead of last-write-wins;
- field evidence that resolves through Observation to SourceObject plus explicit lineage edges;
- contract-aligned `planned`, `high_confidence_inference`, `conflict`, DayLedger/Recall `partial`, and data-insufficient states;
- SourceLocator degradation history (`available -> moved/missing/permission_revoked/deleted`) without inventing availability;
- tombstone precedence during rebuild so deleted objects do not return through Today or FTS Recall.

The committed fixture is fully synthetic: `tests/fixtures/core/synthetic_core_day.json`.

## Run

From the repository root:

```powershell
python scripts/dev/core/run_demo.py
python -m unittest discover -s tests/core -p "test_*.py" -v
```

To inspect a local SQLite database (the path is ignored by repository rules):

```powershell
python scripts/dev/core/run_demo.py --database scripts/dev/core/.tmp/core-demo.sqlite3
```

The demo deliberately deletes the synthetic river-walk source, rebuilds every projection, and verifies that the deleted Event is not resurrected.

## Explicit non-production boundaries

This package does **not** implement or claim evidence for SQLCipher, Raw Vault atomic files, Keychain/Keystore, physical/cryptographic erasure, system Health/Location APIs, LAN sync, network APIs, real model calls, mobile lifecycle recovery, performance or release readiness. Its deletion job `completed` state means the single-database synthetic Oracle projection completed; it is not a multi-device deletion proof.

Revision snapshots retain synthetic text so the Oracle can prove replay and tombstone precedence. Production content erasure requires the approved encrypted storage/Raw Vault design and DEL/SEC evidence. No real user data may be loaded here.

## Contract mappings and gaps

- The machine contract does not expose generic Event `inferred`; the Oracle uses FieldEvidence/Observation `inferred` and Event `high_confidence_inference`.
- `partial` is a DayLedger/Recall range property, never an Event fact claim.
- A user undo is represented as a new `user_edit` revision with internal `undo_of_revision` metadata because the current reason enum has no `undo` value.
- Internal replay and locator-history details are documented in [CONTRACT_GAPS.md](CONTRACT_GAPS.md); `packages/contracts/**` remains unchanged.
