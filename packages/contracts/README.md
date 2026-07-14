# Ameme shared contracts

This directory is the machine-readable source of truth for MVP domain objects and HTTP/Agent-facing APIs.

## Contents

- `schemas/ameme-domain.schema.json`: JSON Schema 2020-12 definitions for immutable inputs, source locators, events, day ledgers, recall, grants, sync, deletion, export and derived outputs.
- `api/openapi.yaml`: transport contract; business invariants remain in the domain schema and architecture docs.
- `examples/synthetic-day.json`: non-personal synthetic contract bundle used by validation and tests.
- `examples/invalid-contracts.json`: mutation-based negative fixtures proving required/security fields cannot be silently ignored.
- `../../scripts/validation/validate_contracts.py`: offline syntax, reference, OpenAPI and example validation.

## Versioning rules

1. `schema_version` identifies the persisted object format; the MVP starts at integer `1`.
2. Additive optional properties are backward compatible. New required properties, changed meanings or removed enum values require a new major schema and migration.
3. Readers must reject unsupported major versions with `SCHEMA_UNSUPPORTED`; they must not guess or drop security fields.
4. Unknown optional fields are preserved across sync when possible. Security decisions use only understood fields.
5. API clients send `Idempotency-Key` for writes. Reusing a key with different content returns `IDEMPOTENCY_CONFLICT`.
6. `SourceLocator` is an opaque, device-scoped index to an original local/system object; it is not a portable absolute path and must degrade to moved/missing/revoked instead of fabricating availability.

## Validation

```powershell
python scripts/validation/validate_contracts.py
```

The validator is intentionally offline and uses only the Python standard library plus the workspace's YAML parser. It validates the schema shape, local `$ref` targets, OpenAPI operation basics and the synthetic bundle against the supported JSON Schema subset.
