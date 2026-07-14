# Ameme AI processing reference

> Status: **non-production, no-network reference implementation only**. This package proves deterministic task, policy and EventCandidate semantics with synthetic fixtures. It does not call or benchmark a real model and must not be shipped in iOS, Android or an Agent runtime.

## Scope proved in this segment

- A versioned `TaskRegistry` for the accepted T01–T12 catalogue; T12 long-term memory remains disabled.
- Immutable `PromptEnvelope`, provider-neutral `ProviderAdapter`, privacy/policy gate with an explicit provider-adapter allow-list, stable errors and allow-list observability without prompt/body/raw text.
- R0 time/timezone normalization, exact hash deduplication, conservative structured deduplication, explicit `planned` semantics and `UserAddendum` conversion.
- Internal `EventDraft` plus current machine-contract `EventCandidate` output. JSON Schema, current semantic validation and deterministic evidence/space/confidence checks run before acceptance.
- Restricted content never reaches an external provider by default. Budget, provider, schema or evidence failures preserve the R0 candidate; insufficient summary input returns `no_summary`.
- `FakeSyntheticProvider` reads only committed synthetic responses and contains no network client.

## Rule priority

Rules execute in this order; a later rule cannot weaken an earlier boundary:

1. Consume a caller-provided authorization snapshot, require a non-empty purpose marker, and validate registered task, space, deleted evidence and manifest.
2. Validate each Observation against the current machine Schema.
3. Before external generation, enforce restricted-data blocking, cloud/location policy, the provider-adapter allow-list and budget.
4. Normalize explicit offsets/timezone without using wall-clock time.
5. Exact dedup uses `(space_id, content_hash)`; safe dedup requires equal structured value, time, fact status and intent.
6. User correction/user assertion wins only for the field it directly asserts; superseded evidence IDs remain in lineage.
7. `planned` stays planned and is separated from happened evidence.
8. Different space, different explicit intent or disjoint time stays separate; only matching action/time with complementary evidence may merge.
9. Emotion, relationship and importance are salience only. They are excluded from factual confidence and never alter grants/provider policy.
10. Provider output must remain a Candidate, cite existing same-space Observation IDs and cannot claim confidence or status from an Observation that supports a different field value.
11. Failure falls back to candidate or `no_summary`; it never invents a confirmed Event.

## Machine contract boundary

`EventDraft` is an internal reference dataclass because the current machine Schema has no persisted EventDraft object. Only its `candidate` member is an `EventCandidate/v1`. The validator does not fabricate SourceObjects when an AI task receives an Observation bundle: it validates Observation and EventCandidate shapes separately, runs the current candidate semantic validator, then enforces evidence lineage within the supplied bundle.

## Authorization boundary

`PolicyContext` is a caller-provided snapshot of an authorization decision. Its non-empty `purpose`, allowed processing locations and `allowed_provider_adapters` are enforced as task inputs, but this reference package does not resolve identity, consent, revocation or persisted `AccessGrant` objects. Callers must supply only a currently authorized snapshot. The complete Grant evaluation and lifecycle remain production work.

## Run

From the repository root:

```powershell
python -m unittest discover -s tests/ai -p "test_*.py" -v
python scripts/dev/ai/run_ai_reference_demo.py
python scripts/validation/run_contract_quality.py
```

All committed input is under `tests/fixtures/ai/` and is synthetic. The demo emits deterministic synthetic candidate metadata and content-free observer records.

## Explicit non-claims and remaining work

This package does not prove model quality, provider privacy/retention, prompt quality, OCR/transcription, mobile performance, network behavior, token cost, production isolation, complete Grant authorization or release readiness. It does not implement Summary generation or R2/R3 routing; it only proves their safe no-summary fallback. Real provider evaluation requires a separate approved Spike, PIA, fixed eval comparison and deletion evidence.
