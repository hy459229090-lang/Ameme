import assert from "node:assert/strict";
import test from "node:test";

import { DeterministicFakeProvider } from "../src/fake-provider.js";
import { InferenceGateway } from "../src/gateway.js";
import { createInferenceServer } from "../src/server.js";
import { syntheticRequest } from "./fixtures.js";

test("HTTP endpoint returns strict summary and content-free errors", async (context) => {
  const server = createInferenceServer(
    new InferenceGateway(new DeterministicFakeProvider(), { timeoutMs: 1_000 }),
  );
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  const address = server.address();
  assert.ok(address && typeof address === "object");
  const base = `http://127.0.0.1:${address.port}`;

  const ok = await fetch(`${base}/v1/day-summary`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(syntheticRequest()),
  });
  assert.equal(ok.status, 200);
  const okBody = await ok.json() as { summary: { ledger_revision: number } };
  assert.equal(okBody.summary.ledger_revision, 7);
  assert.equal(ok.headers.get("cache-control"), "no-store");

  const raw = await fetch(`${base}/v1/day-summary`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...syntheticRequest(), data_class: "raw" }),
  });
  assert.equal(raw.status, 400);
  const rawText = await raw.text();
  assert.deepEqual(JSON.parse(rawText), { error: { code: "DATA_CLASS_DENIED", retryable: false } });
  assert.doesNotMatch(rawText, /Synthetic contract test passed/);

  const invalidUtf8 = await fetch(`${base}/v1/day-summary`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: new Uint8Array([0xff]),
  });
  assert.equal(invalidUtf8.status, 400);
  assert.deepEqual(await invalidUtf8.json(), { error: { code: "INVALID_REQUEST", retryable: false } });
});
