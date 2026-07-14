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
