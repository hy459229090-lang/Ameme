# Workflow states and transitions

## Connection states

`unavailable`, `unpaired`, `pairing_pending`, `ready`, `partial`, `offline`, `denied`, `revoked`, `expired`, and `error` are user-visible semantic states. Do not collapse them into a generic failure.

## Pair

1. Call `status` only if connection state is unknown.
2. Prepare a grant request naming caller, device/host, purpose, space, data classes, duration, processing location, and whether implicit invocation is requested.
3. Show those terms to the user and require explicit confirmation.
4. Call `pair`; poll or deep-link only as the host supports.
5. On success, show expiry and revocation path. On pending/denied/error, keep the primary task usable without memory.

The product default is one caller, one space, named purposes including `autonomous_memory`, Structured data, and 30 days. Raw and Restricted data are excluded until separately approved.

## Task start: implicit context

Implicit context is allowed only when all are true: pairing is ready; the caller identity matches; the grant includes `task_context`; the space is unambiguous; data class and processing location are permitted; and the grant has not expired or been revoked.

Call `get_context` with the default window and budget. If multiple spaces or Restricted data would materially improve the result, continue with the smaller scope and offer expansion rather than expanding silently.

## Explicit recall

Translate the user's wording into query, time range, spaces, and requested result type. Use `recall`. Return the result with `complete`, `partial`, or `unknown` coverage and stable missing-scope reasons.

## Task end: capture

Capture durable memory automatically when an exact-scope `autonomous_memory` grant is active. Write a direct Event/Revision with evidence class, lineage, actor, and deterministic idempotency key; show it in Agent activity with undo. User statements may be durable `user_asserted`; inference remains `inferred`; words such as `final` or `done` never become confirmation evidence. Without the grant, confirm before writing.

## Feedback

Use `feedback` for correction, rejection, relevance feedback, suspected prompt injection, or source conflict. Keep the old evidence lineage; a correction creates a new feedback/revision event.

## Failure and recovery

- `unpaired`, `denied`, `revoked`, `expired`: continue the task and explain the smallest recovery action.
- `offline`: use cached ContextPack only until its expiry; queue eligible captures and label them local/queued.
- `partial`: show what scope is missing; do not convert empty results into “nothing happened”.
- `error`: retry only idempotent operations; avoid repeated consent prompts or duplicate captures.
