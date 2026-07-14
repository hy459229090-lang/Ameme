import assert from "node:assert/strict";
import test from "node:test";

import { OpenAIResponsesProvider } from "../src/openai-provider.js";
import { syntheticRequest } from "./fixtures.js";

const enabled =
  process.env.AMEME_RUN_OPENAI_LIVE === "1" &&
  Boolean(process.env.OPENAI_API_KEY) &&
  Boolean(process.env.AMEME_SAFETY_HMAC_KEY) &&
  Boolean(process.env.AMEME_OPENAI_MODEL);

test(
  "live OpenAI Responses provider returns a schema-valid synthetic summary",
  { skip: enabled ? false : "requires explicit live opt-in, key, safety secret and model" },
  async () => {
    const provider = new OpenAIResponsesProvider({
      apiKey: process.env.OPENAI_API_KEY!,
      safetyHmacKey: process.env.AMEME_SAFETY_HMAC_KEY!,
      model: process.env.AMEME_OPENAI_MODEL!,
    });
    const result = await provider.generate(syntheticRequest(), AbortSignal.timeout(30_000));
    assert.equal(result.provider, "openai");
    assert.equal(result.model, process.env.AMEME_OPENAI_MODEL);
    assert.ok(result.response_id.startsWith("resp_"));
  },
);
