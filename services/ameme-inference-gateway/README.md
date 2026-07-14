# Ameme inference gateway

Stateless MVP service for producing one strict daily summary from a caller-authorized, **structured-only** Event projection. The gateway is not a memory cloud: it has no database, does not accept raw photos/audio/chat/browser data, does not persist request or response bodies, and does not log event content.

## Boundary

Accepted input is `ameme.day-event-projection.v1`: one ledger ID/revision, one local date/timezone, a privacy-scoped subject reference, and at most 100 structured Event projections. Every projection carries a bounded title/detail, evidence/fact state and sensitivity. Fact state keeps `planned` distinct: a calendar plan is not rewritten as something that happened, and summaries may surface it only as an open loop. `raw`, unknown fields, `restricted`, cross-date events, over-size requests and output budgets outside 256–1,200 tokens fail before provider invocation.

Output is `ameme.day-summary.v1`. The gateway validates provider output again, rejects invented event references, and binds the trusted `ledger_id`, `ledger_revision`, local date, `ameme.day-summary-rules.v1`, provider, model ID, provider-interface version and response ID. It does not write the summary to any store.

The HTTP surface is `POST /v1/day-summary`; `GET /healthz` reports only process health. This service currently assumes an authenticated upstream and must not be exposed directly to the public Internet. Mobile clients never receive or hold the model API key.

## Providers

- `DeterministicFakeProvider`: synthetic tests/local integration only. Starting the HTTP service with it requires both `AMEME_INFERENCE_PROVIDER=fake` and `AMEME_ALLOW_FAKE_PROVIDER=true`.
- `OpenAIResponsesProvider`: official Responses API with strict Structured Outputs. It sets `store:false`, `text.format={type:"json_schema", strict:true}`, `reasoning.effort=low`, a bounded `max_output_tokens`, and an HMAC-derived stable `safety_identifier`. Refusal, incomplete response, timeout and malformed output fail closed.

The OpenAI provider defaults to `gpt-5.6-luna` for the first cost-sensitive MVP evaluation and allows `AMEME_OPENAI_MODEL` override. `OPENAI_API_KEY` and a high-entropy `AMEME_SAFETY_HMAC_KEY` of at least 32 UTF-8 bytes are read only from the gateway environment. The current API shape follows the official [Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs), [Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create), and [stateless Responses guidance](https://developers.openai.com/api/docs/guides/migrate-to-responses).

`store:false` prevents creation of a stored Responses object; it does not turn an external inference provider into local execution or supersede the provider's data-processing terms. Enabling OpenAI therefore still requires the product's cloud-processing consent, privacy and security review. Only the bounded structured projection is sent; `subject_ref`, ledger ID and request ID are not included in model input, and the provider sees only the HMAC-derived safety identifier.

## Run and verify

```powershell
cd services/ameme-inference-gateway
npm.cmd ci
npm.cmd run check
```

Start locally only after explicitly selecting a provider:

```powershell
$env:AMEME_INFERENCE_PROVIDER = "openai"
$env:OPENAI_API_KEY = "..."
$env:AMEME_SAFETY_HMAC_KEY = "a high-entropy server-only secret"
npm.cmd start
```

The live OpenAI test is skipped unless `AMEME_RUN_OPENAI_LIVE=1`, `OPENAI_API_KEY`, `AMEME_SAFETY_HMAC_KEY`, and `AMEME_OPENAI_MODEL` are all set. A skip is evidence that only the local/provider-contract path was verified; it is not evidence of a successful real-model call.

## Operations, cost and rollback

Routine metadata is limited to request ID, ledger revision, event count, outcome and stable error code. No title, detail, subject reference, ledger ID, API key or provider body belongs in logs. Rate limiting, user authentication, production deployment, cloud resources, response retention and billing enforcement remain upstream/release responsibilities.

Cost is bounded per call by the input byte/event limits and `output_token_budget`; actual pricing and model quality require a reviewed live-model evaluation. Rollback is process-level: stop this service or route summary generation back to the deterministic/local implementation. There is no data migration because the gateway persists nothing.
