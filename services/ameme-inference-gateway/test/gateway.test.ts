import assert from "node:assert/strict";
import test from "node:test";

import { DeterministicFakeProvider } from "../src/fake-provider.js";
import { GatewayError } from "../src/errors.js";
import { InferenceGateway } from "../src/gateway.js";
import type { InferenceProvider } from "../src/provider.js";
import { syntheticRequest } from "./fixtures.js";

async function expectCode(code: string, action: () => Promise<unknown>): Promise<void> {
  await assert.rejects(action, (error: unknown) => error instanceof GatewayError && error.code === code);
}

test("deterministic fake binds ledger, rules and model versions without content logs", async () => {
  const records: unknown[] = [];
  const gateway = new InferenceGateway(new DeterministicFakeProvider(), {
    timeoutMs: 1_000,
    logger: (record) => records.push(record),
  });
  const request = syntheticRequest();
  const first = await gateway.summarizeDay(request);
  const second = await gateway.summarizeDay(request);
  assert.deepEqual(first, second);
  assert.equal(first.ledger_revision, 7);
  assert.equal(first.rules_version, "ameme.day-summary-rules.v1");
  assert.equal(first.model.model_id, "ameme-fake-summary-v1");
  assert.ok(first.open_loops.some((item) => item.event_ids.includes("evt_synthetic_plan")));
  assert.ok(!first.highlights.some((item) => item.event_ids.includes("evt_synthetic_plan")));
  assert.ok(!first.progress.some((item) => item.event_ids.includes("evt_synthetic_plan")));
  const logs = JSON.stringify(records);
  assert.doesNotMatch(logs, /Synthetic contract test passed|subject_synthetic|ledger_synthetic/);

  const loggerFailure = new InferenceGateway(new DeterministicFakeProvider(), {
    timeoutMs: 1_000,
    logger: () => { throw new Error("synthetic logger failure"); },
  });
  assert.equal((await loggerFailure.summarizeDay(request)).ledger_revision, 7);
});

test("raw, restricted and unknown raw fields fail before provider invocation", async () => {
  let calls = 0;
  const provider: InferenceProvider = {
    async generate() {
      calls += 1;
      throw new Error("must not run");
    },
  };
  const gateway = new InferenceGateway(provider, { timeoutMs: 1_000 });
  const raw = { ...syntheticRequest(), data_class: "raw" };
  await expectCode("DATA_CLASS_DENIED", () => gateway.summarizeDay(raw));
  const restricted = syntheticRequest() as unknown as Record<string, unknown>;
  (restricted.events as Array<Record<string, unknown>>)[0]!.sensitivity = "restricted";
  await expectCode("SENSITIVITY_DENIED", () => gateway.summarizeDay(restricted));
  const rawField = syntheticRequest() as unknown as Record<string, unknown>;
  (rawField.events as Array<Record<string, unknown>>)[0]!.raw_content = "forbidden";
  await expectCode("INVALID_REQUEST", () => gateway.summarizeDay(rawField));
  const naiveTime = syntheticRequest() as unknown as Record<string, unknown>;
  (naiveTime.events as Array<Record<string, unknown>>)[0]!.event_time = "2026-07-14T10:00:00";
  await expectCode("INVALID_REQUEST", () => gateway.summarizeDay(naiveTime));
  const inventedFactStatus = syntheticRequest() as unknown as Record<string, unknown>;
  (inventedFactStatus.events as Array<Record<string, unknown>>)[0]!.fact_status = "completed";
  await expectCode("INVALID_REQUEST", () => gateway.summarizeDay(inventedFactStatus));
  assert.equal(calls, 0);
});

test("event, byte and output budgets fail closed", async () => {
  const gateway = new InferenceGateway(new DeterministicFakeProvider(), { timeoutMs: 1_000 });
  const tooMany = syntheticRequest();
  tooMany.events = Array.from({ length: 101 }, (_, index) => ({
    ...tooMany.events[0]!,
    event_id: `evt_${index}`,
  }));
  await expectCode("INPUT_LIMIT_EXCEEDED", () => gateway.summarizeDay(tooMany));
  const badBudget = { ...syntheticRequest(), output_token_budget: 1_201 };
  await expectCode("OUTPUT_BUDGET_INVALID", () => gateway.summarizeDay(badBudget));
  const tooLarge = syntheticRequest() as unknown as Record<string, unknown>;
  tooLarge.padding = "x".repeat(70_000);
  await expectCode("INPUT_LIMIT_EXCEEDED", () => gateway.summarizeDay(tooLarge));
});

test("Android and region timezone identifiers are accepted while invalid offsets fail closed", async () => {
  const gateway = new InferenceGateway(new DeterministicFakeProvider(), { timeoutMs: 1_000 });
  for (const timezone of ["GMT", "UTC", "UT", "+08:00", "GMT+08:00", "Asia/Shanghai", "Etc/GMT-8"]) {
    const result = await gateway.summarizeDay({ ...syntheticRequest(), timezone });
    assert.equal(result.ledger_revision, 7);
  }
  for (const timezone of ["", "GMT+18:01", "+17:99", "+99:00", "not a timezone"]) {
    await expectCode(
      "INVALID_REQUEST",
      () => gateway.summarizeDay({ ...syntheticRequest(), timezone }),
    );
  }
});

test("timeout, malformed output and provider budget excess fail closed", async () => {
  const timeoutProvider: InferenceProvider = {
    async generate(_request, signal) {
      await new Promise<void>((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
      });
      throw new Error("unreachable");
    },
  };
  await expectCode(
    "PROVIDER_TIMEOUT",
    () => new InferenceGateway(timeoutProvider, { timeoutMs: 100 }).summarizeDay(syntheticRequest()),
  );

  const malformed: InferenceProvider = {
    async generate() {
      return {
        output: { headline: "invented", overview: "", highlights: [{ text: "x", event_ids: ["evt_missing"] }], progress: [], open_loops: [] },
        provider: "bad",
        model: "bad",
        provider_version: "bad",
        response_id: "bad",
        output_tokens: 1,
      };
    },
  };
  await expectCode(
    "PROVIDER_OUTPUT_INVALID",
    () => new InferenceGateway(malformed, { timeoutMs: 1_000 }).summarizeDay(syntheticRequest()),
  );

  const plannedAsFact: InferenceProvider = {
    async generate(request) {
      const result = await new DeterministicFakeProvider().generate(request, new AbortController().signal);
      return {
        ...result,
        output: {
          ...result.output,
          highlights: [{ text: "The plan happened", event_ids: ["evt_synthetic_plan"] }],
        },
      };
    },
  };
  await expectCode(
    "PROVIDER_OUTPUT_INVALID",
    () => new InferenceGateway(plannedAsFact, { timeoutMs: 1_000 }).summarizeDay(syntheticRequest()),
  );

  const expensive: InferenceProvider = {
    async generate(request) {
      const result = await new DeterministicFakeProvider().generate(request, new AbortController().signal);
      return { ...result, output_tokens: request.output_token_budget + 1 };
    },
  };
  await expectCode(
    "OUTPUT_BUDGET_EXCEEDED",
    () => new InferenceGateway(expensive, { timeoutMs: 1_000 }).summarizeDay(syntheticRequest()),
  );

  const invalidUsage: InferenceProvider = {
    async generate(request) {
      const result = await new DeterministicFakeProvider().generate(request, new AbortController().signal);
      return { ...result, output_tokens: Number.NaN };
    },
  };
  await expectCode(
    "PROVIDER_OUTPUT_INVALID",
    () => new InferenceGateway(invalidUsage, { timeoutMs: 1_000 }).summarizeDay(syntheticRequest()),
  );
});
