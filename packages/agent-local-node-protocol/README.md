# Agent Local Node protocol v1

> Status: language-neutral application and paired-channel contract plus **non-production Python executable specification**. It is not an Android/iOS runtime, pairing system or production security certification.

This package defines the replaceable boundary between an Agent adapter and an Ameme Local Node. The normative application envelope schema is [`../contracts/schemas/ameme-agent-local-node.schema.json`](../contracts/schemas/ameme-agent-local-node.schema.json); Python also freezes the exact paired-channel hello/frame semantics and materializes synthetic conformance tests.

## Envelope and scope

Every request is one UTF-8 JSON object on one line:

```text
protocol_version + request_id + control + payload
```

`control` contains caller, Grant reference, purpose, spaces, memory types, operation, a domain-separated idempotency slot, and the canonical payload digest. `control.spaces` and `control.memory_types` are the **minimal scope of this request** and must exactly equal the scope derived from `payload`. They are not the entire Grant. An authenticated session may bind a Grant whose spaces/types are a superset; the request is authorized when its minimal scope is a subset of that Grant.

The v1 public operation set is deliberately limited to the existing EventNode semantics:

- `get_event`
- `create_event`
- `append_revision`
- `undo_capture`
- `visible_events`
- `set_policy_blocked`

Internal queue/storage methods are not promoted to remote Agent APIs. `undo_capture` carries an opaque undo token, not the store's internal undo record. Raw files and photo/audio bytes never use this JSON line.

## Paired channel envelope

`ameme.agent-local-node.channel.v1` is the authenticated TLS envelope around the unchanged application line. Pairing material has exact fields `channel_protocol/endpoint_ref/credential_ref/expected_device_id/session_binding_ref/pairing_id/host/port/tls_certificate_sha256`; it contains references and a DER SHA-256 pin, never the pairing secret.

The Host requires TLS 1.3 and verifies the pinned certificate before sending `client_hello`. Client/server hello messages are sequence zero and HMAC-bind pairing ID, expected device, session-binding reference, certificate pin, both 256-bit nonces, server session ID and sorted supported operations. Both sides derive a per-session key from the verified hello transcript.

Application frames then carry exact fields plus a session-key HMAC:

- request: `session_id`, strictly increasing positive signed-64-bit `sequence`, fresh 256-bit `nonce`, `application_protocol`, application digest/base64, and the inner request's durable `idempotency_slot` as `idempotency_ref`;
- response: the same session/sequence/idempotency reference, the request nonce, a fresh response nonce, and the strict `ameme.agent-local-node.v1` response line.

Sequence, nonce, session, application digest, response/request ID or idempotency reference mismatch fails closed. A new TLS connection creates a new session and restarts its sequence; durable retry identity remains the domain-separated inner `idempotency_slot`. This contract does not define plaintext transport or a listening address, and it does not make control fields authentication evidence.

## Canonical bytes, digests and replay

`ameme-canonical-json-v1` is:

- UTF-8 without BOM;
- object keys sorted by Unicode code point;
- array order preserved; scope arrays must already be sorted and unique;
- no insignificant whitespace and literal non-ASCII UTF-8 strings;
- base-10 safe integers only; floats, NaN and Infinity are rejected;
- duplicate JSON keys, invalid UTF-8, NUL, unknown fields and unsupported versions fail closed.

`payload_digest` and successful `result_digest` are `sha256_` plus lowercase SHA-256 of canonical bytes. A caller-controlled raw idempotency key is transformed locally using:

```text
sha256("ameme:idempotency:v1:agent-local-node-wire:<operation>\0<raw-key>")
```

Only `idem_` plus the lowercase digest appears on the wire. A slot replay with identical caller/Grant/purpose/scope/operation/payload digest is safe replay; the same slot with changed semantics is `IDEMPOTENCY_CONFLICT`. `request_id` binds one request to one response and is not a substitute for durable idempotency.

## Limits and stable errors

- Request line: 65,536 UTF-8 bytes.
- Canonical payload: 32,768 UTF-8 bytes.
- Response line: 524,288 UTF-8 bytes.
- Scope arrays: at most 8 sorted unique entries.
- Visible-event result request: at most 100 items.
- Event/revision content: at most 4,000 Unicode code points.

Stable policy/store codes include `AUTH_REQUIRED`, `GRANT_REVOKED`, `GRANT_EXPIRED`, `PURPOSE_DENIED`, `SPACE_DENIED`, `DATA_TYPE_DENIED`, `NOT_VISIBLE`, `OPERATION_UNSUPPORTED`, `REVISION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `SCHEMA_UNSUPPORTED` and `TEMPORARILY_UNAVAILABLE`. Validation/digest/size codes and each code's `retryable` value are frozen by the executable specification: only `TEMPORARILY_UNAVAILABLE` and `INTERNAL_ERROR` are retryable in v1. Error responses contain only `code` and `retryable`; `NOT_VISIBLE` deliberately does not reveal whether a target exists, is deleted, belongs to another space, or is outside authorization. A channel that recognizes v1 but does not implement one of its operations returns non-retryable `OPERATION_UNSUPPORTED`; `SCHEMA_UNSUPPORTED` is reserved for an unsupported protocol/schema version.

## Version rules

`protocol_version` must equal `ameme.agent-local-node.v1`; unknown versions return `SCHEMA_UNSUPPORTED`. Because v1 rejects unknown fields and uses exhaustive operation/error enums, adding a field, operation, error meaning or canonicalization rule requires a reviewed new protocol version and new golden vectors. Implementations must not guess fields or downgrade an unknown major.

## Validation

All committed values are synthetic:

```powershell
python scripts/validation/generate_agent_local_node_vectors.py --check
python scripts/validation/validate_agent_local_node_protocol.py
python -m unittest discover -s packages/agent-local-node-protocol/tests -p "test_*.py" -v
```

`tests/fixtures/agent/agent-local-node-conformance-v1.json` covers six valid operations, malformed/digest/scope/size failures, Grant-superset/request-subset authorization, policy denials, response binding, no-detail `NOT_VISIBLE`, and replay conflicts.

## Explicit non-claims

The application/channel contract and synthetic Python checks provide no evidence for LAN discovery, Android endpoint reachability, pairing-material issuance, key storage, Android background lifecycle, physical-device behavior or production readiness. Host-side TLS integration tests prove only the Python client against a synthetic TLS server. Swift/Kotlin implementations must consume the same contract after the Android server design is accepted; production Apps must not start or embed this Python package.
