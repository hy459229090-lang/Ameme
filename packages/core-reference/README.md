# Ameme local core reference/oracle

> Status: **non-production reference/oracle only**. This package validates the MVP data model and closed-loop semantics with synthetic data. It is not the iOS/Android runtime and must not be presented as production storage.

## What it proves

The Python 3.12 implementation uses SQLite plus the pinned
`cryptography==46.0.7` AES-GCM implementation to execute this chain:

```text
AcquisitionContract
  -> SourceObject + optional UserAddendum
  -> Observation
  -> EventCandidate
  -> append-only EventRevision / current Event projection
  -> append-only EpisodeRevision / current Episode projection
  -> DayLedger Today view
  -> date/FTS Recall with day + field evidence
  -> encrypted Raw Vault manifest/file lifecycle
  -> durable processing/sync/delete/export/recompute jobs
  -> source tombstone/deletion projection
  -> deletion lineage impact + local/replica proof state
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
- a SourceLocator that stays separate from Raw Vault ciphertext and manifest metadata;
- AES-256-GCM per-object encryption with caller-injected 32-byte keys, unique 96-bit nonces, plaintext/ciphertext SHA-256, atomic temp-file/fsync/rename writes, quota checks, TTL cleanup and reopen recovery;
- AAD v2 rebuilt from manifest columns—not trusted from `aad_json`—and binding identity, private path, key ID, nonce, content hash/size, MIME type, retention/TTL and selected-sync policy;
- fail-closed behavior when `cryptography`, a key provider or a valid 256-bit key is unavailable—there is no plaintext or home-grown crypto fallback;
- durable job priority, command idempotency, leases, attempts, deterministic exponential backoff and crash/reopen lease recovery;
- two-phase Raw deletion: first commit `delete_pending`, then remove the ciphertext, then commit the final manifest state and reconcile proof; every persisted interruption point is recoverable on reopen;
- deletion impact calculated from lineage, with `completed` allowed only after Raw/structured/derived/index cleanup and every declared replica acknowledgement are satisfied;
- tombstone precedence during rebuild so deleted objects do not return through Today or FTS Recall.

The committed fixture is fully synthetic: `tests/fixtures/core/synthetic_core_day.json`.

## Run

From the repository root:

```powershell
python -m pip install -r scripts/requirements-dev.txt
python scripts/dev/core/run_demo.py
python -m unittest discover -s tests/core -p "test_*.py" -v
```

To inspect a local SQLite database (the path is ignored by repository rules):

```powershell
python scripts/dev/core/run_demo.py --database scripts/dev/core/.tmp/core-demo.sqlite3
```

The demo deliberately deletes the synthetic river-walk source, rebuilds every projection, and verifies that the deleted Event is not resurrected. The Raw Vault and durable queue failure matrix is in `tests/core/test_raw_vault_and_queues.py`; all inputs are synthetic.

## Explicit non-production boundaries

This package is an executable semantic and failure oracle. It is **not** the Mobile Local Node and must not be shipped or reused as production storage.

- SQLite metadata and structured content are plaintext in this reference database. SQLCipher-at-rest, migration and backup behavior are unimplemented.
- AES-GCM proves the reference file format and fail-closed calls only. The key is supplied by the test/caller and held in process memory; OS Data Protection, iOS Keychain/Secure Enclave, Android Keystore, key rotation, backup exclusion and secure key deletion are unimplemented.
- Schema v3 labels pre-AAD-v2 rows as `aad_version=1` and fails closed with an explicit re-encryption-migration error. This Oracle does not silently reinterpret or automatically re-encrypt legacy ciphertext, so upgrading an existing reference database does not make legacy Raw objects readable.
- On POSIX, the reference fsyncs the encrypted file and containing directory around atomic rename. Python/Windows does not expose the same directory-fsync primitive here, so Windows proves same-volume `os.replace` behavior and recovery cleanup, not power-loss durability. Mobile filesystem durability still requires platform Spike evidence.
- The manifest contains non-content metadata (`key_id`, nonce, hashes, sizes, retention, relative private path and authenticated identifiers), never the key or plaintext. Hashes, sizes and MIME types can still reveal information, and this plaintext reference database does not protect them. Directory permissions are best-effort process calls, not evidence for app-sandbox/ACL enforcement. The Oracle has no logging pipeline; production redaction and access audit are not proven.
- Quota is a configured ciphertext-byte cap and TTL is caller-driven cleanup. Production storage pressure signals, background schedulers and OS lifecycle recovery are unimplemented.
- Durable queues prove SQLite state transitions for one local process/database. They do not prove mobile schedulers, multi-process fairness, network delivery, cloud queues or real peer synchronization.
- A deletion job reaches `completed` only after every affected Raw manifest is in a final deleted state, its ciphertext path is absent, local synthetic cleanup checks pass and every replica ID explicitly declared to this Oracle is acknowledged. A missing file behind `delete_pending` remains proof-incomplete until manifest reconciliation. This is useful proof-state semantics, not evidence that real devices received, verified or cryptographically attested deletion. Unknown/offline peers, physical/cryptographic erasure and account-wide discovery remain unimplemented.
- System Health/Location APIs, LAN transport, network APIs, real model calls, performance, accessibility, observability and release readiness are outside this package.

Revision snapshots retain synthetic text so the Oracle can prove replay and tombstone precedence. Production content erasure requires the approved encrypted storage/Raw Vault design plus DEL/SEC evidence. No real user data may be loaded here.

## Contract mappings and gaps

- The machine contract does not expose generic Event `inferred`; the Oracle uses FieldEvidence/Observation `inferred` and Event `high_confidence_inference`.
- `partial` is a DayLedger/Recall range property, never an Event fact claim.
- A user undo is represented as a new `user_edit` revision with internal `undo_of_revision` metadata because the current reason enum has no `undo` value.
- Internal replay, locator history, Raw manifest, durable queue and deletion-proof details are documented in [CONTRACT_GAPS.md](CONTRACT_GAPS.md); `packages/contracts/**` remains unchanged.
