# Ameme shared contracts

This directory is the machine-readable source of truth for MVP domain objects and HTTP/Agent-facing APIs.

## Contents

- `schemas/ameme-domain.schema.json`: JSON Schema 2020-12 definitions for immutable inputs, source locators, events, day ledgers, recall, grants, sync, deletion, export and derived outputs.
- `schemas/ameme-agent-local-node.schema.json`: strict JSON Schema 2020-12 request/response envelope for the versioned Agent-to-Local-Node application RPC. Its request scope is minimal and payload-derived; an authenticated Grant may be a superset.
- `schemas/ameme-coverage.schema.json`: strict planning/research contract for target segments, context taxonomy, source capabilities, synthetic coverage days, evidence-backed coverage observations and explicit context gaps. It is not a production persistence contract or market-size claim.
- `api/openapi.yaml`: transport contract; business invariants remain in the domain schema and architecture docs.
- `examples/synthetic-day.json`: non-personal synthetic contract bundle used by validation and tests.
- `examples/invalid-contracts.json`: mutation-based negative fixtures proving required/security fields cannot be silently ignored.
- `versions/manifest.json`: machine-readable contract release list, compatibility policy and immutable snapshot hashes.
- `versions/v0.1/`: exact JSON Schema and OpenAPI baseline used by the breaking-change gate.
- `../../scripts/validation/validate_contracts.py`: offline syntax, reference, OpenAPI and example validation.
- `../../scripts/validation/validate_coverage_contracts.py`: validates the synthetic context/source matrix, field-authority and hard-gate rules, conservative event compilation and the ban on fabricated coverage percentages or market size.
- `../../scripts/validation/check_contract_compatibility.py`: baseline hash and backward-compatibility diff.
- `../../tests/contracts/`: generated positive/required/unknown/enum checks for every public object plus cross-object invariants.
- `../../tests/harness/`: fixed clock, deterministic IDs, deterministic fault injection and allow-list logger harness.
- `../../tests/security/contract/`: synthetic canary tests for body, secret, path, coordinate and URL-query leakage.
- `../agent-local-node-protocol/`: non-production Python executable specification and tests for canonical JSON, digests, exact request scope, stable errors and deterministic wire vectors.

## Versioning rules

1. `schema_version` identifies the persisted object format; the MVP starts at integer `1`.
2. Additive optional properties are backward compatible. New required properties, changed meanings or removed enum values require a new major schema and migration.
3. Readers must reject unsupported major versions with `SCHEMA_UNSUPPORTED`; they must not guess or drop security fields.
4. Unknown optional fields are preserved across sync when possible. Security decisions use only understood fields.
5. API clients send `Idempotency-Key` for writes. Reusing a key with different content returns `IDEMPOTENCY_CONFLICT`.
6. `SourceLocator` is an opaque, device-scoped index to an original local/system object; it is not a portable absolute path and must degrade to moved/missing/revoked instead of fabricating availability.
7. `ameme.agent-local-node.v1` rejects unknown versions/fields, duplicate keys, floats and invalid UTF-8. Wire changes require a new reviewed protocol version and regenerated cross-language vectors; the schema does not prove discovery, authentication, encryption or LAN behavior.

## Baseline and breaking changes

`versions/v0.1/` is immutable. `versions/manifest.json` records its LF-normalized SHA-256 hashes and the current contract version, so the same snapshot verifies on Windows and Linux. The compatibility gate rejects removed objects/properties/operations, new required fields, removed enum values, tightened constraints, changed references and removed successful API responses. Optional additive fields pass; enum additions pass with a review warning because older exhaustive readers still need an explicit compatibility decision.

To intentionally introduce a breaking contract, create a new major snapshot and migration/rollback evidence, then update the manifest in the same reviewed change. Do not edit an existing snapshot or its stored hash to make the gate pass.

## Validation

```powershell
python scripts/validation/run_contract_quality.py
```

The runner is intentionally offline and Windows-compatible. It executes compatibility diff, Schema/OpenAPI validation, public-object tests, semantic invariants, deterministic harness tests and security-log canaries. All data is synthetic.

Individual gates remain runnable for diagnosis:

```powershell
python scripts/validation/check_contract_compatibility.py
python scripts/validation/validate_contracts.py
python scripts/validation/validate_agent_local_node_protocol.py
python scripts/validation/validate_coverage_contracts.py
python scripts/validation/generate_agent_local_node_vectors.py --check
python -m unittest discover -s tests/contracts -p "test_*.py" -v
python -m unittest discover -s tests/harness -p "test_*.py" -v
python -m unittest discover -s tests/security/contract -p "test_*.py" -v
```

The coverage schema is additive to the frozen v0.1 runtime contract: it supports research and reference compilation without claiming that mobile persistence, real authorization rates, target-user scale or whole-day coverage have been validated. This M1 baseline does not claim C-002/C-003/C-004 completion. Swift DTO, Kotlin DTO and platform/Agent adapter round trips remain mobile and integration deliverables.
