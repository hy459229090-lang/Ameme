# M2 contract gaps observed by the reference/oracle

> Scope: implementation feedback only. No files under `packages/contracts/**` were modified.

## 1. EventRevision replay shape is underspecified

`EventRevision.changes` is currently an unconstrained object. That is sufficient for transport validation but not enough for independent implementations to deterministically rebuild the same Event projection. This Oracle therefore stores an internal full `snapshot_json` beside each immutable revision.

Suggested contract follow-up: define either a typed patch format with field clear/remove semantics or a canonical full-snapshot revision payload, including how evidence changes and field removal are represented.

## 2. SourceLocator history has no machine object

`SourceLocator` describes the current availability state, but the machine contract does not define an immutable degradation/verification history. The Oracle uses an internal `source_locator_history` table so `available -> missing/revoked/deleted` remains traceable.

Suggested contract follow-up: add an optional locator-state event or revision object if locator history must sync or be externally audited.

## 3. Undo has no explicit revision reason

`EventRevision.reason` has `user_edit` and `conflict_resolution`, but no `undo`. The Oracle implements undo as a new `user_edit` revision with internal `undo_of_revision` metadata; it never deletes the reverted revision.

Suggested contract follow-up: either document that mapping or add a backward-compatible `undo` reason in the next contract revision.

## 4. Tombstone is specified semantically but not as a standalone schema

The architecture makes tombstone precedence an invariant, while the schema exposes it mainly through `SyncEnvelope.operation` and `DeletionJob`. The local Oracle needs an internal durable tombstone table to guarantee that projection rebuild cannot resurrect an Event.

Suggested contract follow-up: define the minimum tombstone identity/scope/causal fields if local Core, sync peers and migration tooling must exchange or validate it independently.

## 5. Raw manifest is a sync operation but not a typed object

`SyncEnvelope.operation` includes `raw_manifest`, while the domain contract has no `RawManifest` definition. The Oracle therefore uses an internal manifest with a private relative path, key identifier, unique nonce, authenticated identifiers, plaintext/ciphertext hashes and sizes, retention/TTL, deletion state and selected-sync flag. Raw ciphertext remains outside SQLite and SourceLocator remains a separate source reference.

Suggested contract follow-up: define a metadata-only RawManifest for compatible sync/migration without exposing plaintext, an encryption key or a portable absolute path. Specify nonce uniqueness scope, hash meaning, retention transitions and whether `key_id` is device-local or transferable.

## 6. Durable worker queue semantics are not machine-readable

The contract exposes `processing_state`, `DeletionJob` and `ExportJob` lifecycle states, but it does not define the local processing/sync/delete/export/recompute worker envelope. The Oracle needs internal priority, payload hash, idempotency key, lease owner/expiry, attempt count, max attempts, availability time, bounded backoff and non-content error code fields to prove crash/reopen recovery.

Suggested contract follow-up: keep the local queue implementation private unless components must exchange it. If interoperability is required, specify lease fencing, expiry comparison, retry/backoff rules, terminal failure and which idempotency scope survives process or device restart.

## 7. Deletion proof cannot enumerate every verified outcome

`DeletionJob` includes ordered steps, `pending_replica_ids` and an optional `proof_hash`, but it cannot carry acknowledged replica IDs, the lineage impact set, per-Raw-object cleanup failures or an explicit `proof_complete` assertion. The Oracle keeps these details in internal job evidence and permits `completed` only when local affected objects are cleaned and every replica declared in scope is acknowledged; otherwise it remains `partial_failed`/proof incomplete.

Suggested contract follow-up: define the canonical material covered by `proof_hash`, acknowledgement identity/freshness, required versus discovered peer scope, per-step evidence references and how a completed job is reopened if a previously unknown replica or dependent object appears.
