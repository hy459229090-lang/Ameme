# MCP tool contracts

The MCP server name is `ameme`. Exact wire schemas are versioned in the machine contracts; this document defines Skill-level behavior.

| Tool | Minimum input | Expected output |
|---|---|---|
| `pair` | caller, purposes, spaces, data classes, expiry, processing location, confirmation artifact | grant state, grant id, expiry, revocation route |
| `recall` | query, spaces, time range, limit, requested data class | results, completeness, missing-scope reasons, lineage refs |
| `get_context` | task purpose, active space, time range, item/token budget | caller-bound ContextPack, expiry, completeness, citations |
| `capture` | content or structured event, event time, space, evidence/fact state, lineage refs, optional long-term memory type, idempotency key, autonomous grant id | local/durable/queued state, Event/Revision id, undo window, separate long-term memory compiler state |
| `feedback` | target id, correction/rejection/relevance/security kind, user statement | feedback id, revision/recompute state |
| `status` | caller plus optional grant/source/job selector | semantic state, expiry, scope summary, recoverable action |

## Defaults

Implicit `get_context`: active space, previous 30 days, maximum 12 items, maximum 2,000 host-rendered tokens, ContextPack TTL 15 minutes. Explicit recall follows the user's requested window but confirms materially broader or more sensitive scope.

The current Android Local Node adapter can satisfy `recall` and `get_context` only through bounded `visible_events`: one approved Personal space, Event/structured only, query at most 1,000 code points and result limit at most 100. Returned event title/description are truncated to 240/1,000 code points and exclude user words, source identifiers/labels, locators, paths, and Raw. Arbitrary `get_event`, policy mutation, long-term Memory read/confirmation, and Restricted access are not implied.

## ContextPack

A ContextPack is immutable, short-lived, caller-bound, purpose-bound, space-bound, and grant-bound. It carries manifest/version, creation/expiry, completeness, items, provenance references, and data-class/processing-policy metadata. Do not persist its body in host logs or reuse it for another task.

## Idempotency

For capture, derive the idempotency key from caller, active task id, normalized verified payload hash, target space, and capture mode. Retry the same key after transport failure. Never create a new key merely because the first response was lost.

`capture` never returns a confirmed long-term Memory directly. Its long-term state is one of `not_requested`, `eligible_for_memory_compiler`, or `candidate_user_confirmation_required`. Inference and preference/relationship/health/financial/major-decision types must use the last state.

## Stable failures

Handle at least: `UNPAIRED`, `CONSENT_REQUIRED`, `SCOPE_DENIED`, `GRANT_EXPIRED`, `GRANT_REVOKED`, `SPACE_AMBIGUOUS`, `OFFLINE`, `PARTIAL_COVERAGE`, `CONTEXT_EXPIRED`, `CONFLICT`, `RATE_LIMITED`, and `INTERNAL_ERROR`.
