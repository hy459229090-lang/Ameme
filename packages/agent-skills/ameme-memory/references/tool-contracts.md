# MCP tool contracts

The MCP server name is `ameme`. Exact wire schemas are versioned in the machine contracts; this document defines Skill-level behavior.

| Tool | Minimum input | Expected output |
|---|---|---|
| `pair` | caller, purposes, spaces, data classes, expiry, processing location, confirmation artifact | grant state, grant id, expiry, revocation route |
| `recall` | query, spaces, time range, limit, requested data class | results, completeness, missing-scope reasons, lineage refs |
| `get_context` | task purpose, active space, time range, item/token budget | caller-bound ContextPack, expiry, completeness, citations |
| `capture` | content or structured memory, event time, space, evidence/fact state, lineage refs, idempotency key, autonomous grant id | local/durable/queued state, Event/Revision id, undo window |
| `feedback` | target id, correction/rejection/relevance/security kind, user statement | feedback id, revision/recompute state |
| `status` | caller plus optional grant/source/job selector | semantic state, expiry, scope summary, recoverable action |

## Defaults

Implicit `get_context`: active space, previous 30 days, maximum 12 items, maximum 2,000 host-rendered tokens, ContextPack TTL 15 minutes. Explicit recall follows the user's requested window but confirms materially broader or more sensitive scope.

## ContextPack

A ContextPack is immutable, short-lived, caller-bound, purpose-bound, space-bound, and grant-bound. It carries manifest/version, creation/expiry, completeness, items, provenance references, and data-class/processing-policy metadata. Do not persist its body in host logs or reuse it for another task.

## Idempotency

For capture, derive the idempotency key from caller, active task id, normalized verified payload hash, target space, and capture mode. Retry the same key after transport failure. Never create a new key merely because the first response was lost.

## Stable failures

Handle at least: `UNPAIRED`, `CONSENT_REQUIRED`, `SCOPE_DENIED`, `GRANT_EXPIRED`, `GRANT_REVOKED`, `SPACE_AMBIGUOUS`, `OFFLINE`, `PARTIAL_COVERAGE`, `CONTEXT_EXPIRED`, `CONFLICT`, `RATE_LIMITED`, and `INTERNAL_ERROR`.
