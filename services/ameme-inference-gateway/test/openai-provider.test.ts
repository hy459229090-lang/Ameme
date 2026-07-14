import assert from "node:assert/strict";
import test from "node:test";

import { GatewayError } from "../src/errors.js";
import { DEFAULT_OPENAI_MODEL, OpenAIResponsesProvider, type ResponsesClient } from "../src/openai-provider.js";
import { syntheticRequest } from "./fixtures.js";

function completedResponse(outputText: string) {
  return {
    id: "resp_synthetic_001",
    object: "response",
    created_at: 0,
    status: "completed",
    completed_at: 0,
    error: null,
    incomplete_details: null,
    instructions: null,
    max_output_tokens: 800,
    model: DEFAULT_OPENAI_MODEL,
    output: [{ id: "msg_1", type: "message", status: "completed", role: "assistant", content: [{ type: "output_text", text: outputText, annotations: [], logprobs: [] }] }],
    parallel_tool_calls: false,
    previous_response_id: null,
    prompt_cache_key: null,
    prompt_cache_retention: null,
    reasoning: { effort: "low", summary: null },
    safety_identifier: null,
    service_tier: "default",
    store: false,
    temperature: null,
    text: { format: { type: "json_schema" }, verbosity: "medium" },
    tool_choice: "auto",
    tools: [],
    top_logprobs: 0,
    top_p: null,
    truncation: "disabled",
    usage: { input_tokens: 10, input_tokens_details: { cached_tokens: 0 }, output_tokens: 20, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 30 },
    user: null,
    metadata: {},
    output_text: outputText,
  };
}

test("OpenAI provider sends stateless strict schema, bounded budget and protected stable subject", async () => {
  const seen: Array<{ body: Record<string, unknown>; options: unknown }> = [];
  const output = {
    headline: "Synthetic day",
    overview: "Synthetic only.",
    highlights: [{ text: "Verified", event_ids: ["evt_synthetic_result"] }],
    progress: [],
    open_loops: [],
  };
  const client = {
    responses: {
      async create(body: Record<string, unknown>, options: unknown) {
        seen.push({ body, options });
        return completedResponse(JSON.stringify(output));
      },
    },
  } as unknown as ResponsesClient;
  const provider = new OpenAIResponsesProvider(
    { apiKey: "sk-synthetic", safetyHmacKey: "synthetic-server-only-secret-00000000" },
    client,
  );
  const request = syntheticRequest();
  const first = await provider.generate(request, new AbortController().signal);
  await provider.generate(request, new AbortController().signal);
  assert.equal(first.model, DEFAULT_OPENAI_MODEL);
  assert.equal(seen[0]!.body.store, false);
  assert.deepEqual(seen[0]!.body.reasoning, { effort: "low" });
  assert.equal(seen[0]!.body.max_output_tokens, 800);
  assert.equal((seen[0]!.body.text as { format: { strict: boolean } }).format.strict, true);
  assert.match(String(seen[0]!.body.instructions), /planned are plans, not completed facts/);
  const safety = seen[0]!.body.safety_identifier as string;
  assert.match(safety, /^[0-9a-f]{64}$/);
  assert.doesNotMatch(safety, /subject_synthetic/);
  assert.equal(safety, seen[1]!.body.safety_identifier);
  const modelInput = String(seen[0]!.body.input);
  assert.doesNotMatch(modelInput, /subject_synthetic|ledger_synthetic|req_synthetic/);
  assert.match(modelInput, /evt_synthetic_result/);
});

test("OpenAI refusal and malformed output fail closed", async () => {
  const refusal = completedResponse("");
  refusal.output = [{ id: "msg_1", type: "message", status: "completed", role: "assistant", content: [{ type: "refusal", refusal: "blocked" }] }] as unknown as typeof refusal.output;
  const refusalClient = { responses: { async create() { return refusal; } } } as unknown as ResponsesClient;
  const provider = new OpenAIResponsesProvider(
    { apiKey: "sk-synthetic", safetyHmacKey: "synthetic-server-only-secret-00000000" },
    refusalClient,
  );
  await assert.rejects(
    () => provider.generate(syntheticRequest(), new AbortController().signal),
    (error: unknown) => error instanceof GatewayError && error.code === "PROVIDER_REFUSAL",
  );

  const malformedClient = { responses: { async create() { return completedResponse("not-json"); } } } as unknown as ResponsesClient;
  const malformedProvider = new OpenAIResponsesProvider(
    { apiKey: "sk-synthetic", safetyHmacKey: "synthetic-server-only-secret-00000000" },
    malformedClient,
  );
  await assert.rejects(
    () => malformedProvider.generate(syntheticRequest(), new AbortController().signal),
    (error: unknown) => error instanceof GatewayError && error.code === "PROVIDER_OUTPUT_INVALID",
  );
});
