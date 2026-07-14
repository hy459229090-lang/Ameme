import { DeterministicFakeProvider } from "./fake-provider.js";
import { InferenceGateway } from "./gateway.js";
import { OpenAIResponsesProvider } from "./openai-provider.js";
import type { InferenceProvider } from "./provider.js";
import { createInferenceServer } from "./server.js";

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function providerFromEnvironment(): InferenceProvider {
  const provider = required("AMEME_INFERENCE_PROVIDER");
  if (provider === "openai") {
    const config = {
      apiKey: required("OPENAI_API_KEY"),
      safetyHmacKey: required("AMEME_SAFETY_HMAC_KEY"),
      ...(process.env.AMEME_OPENAI_MODEL ? { model: process.env.AMEME_OPENAI_MODEL } : {}),
    };
    return new OpenAIResponsesProvider(config);
  }
  if (provider === "fake" && process.env.AMEME_ALLOW_FAKE_PROVIDER === "true") {
    return new DeterministicFakeProvider();
  }
  throw new Error("unsupported provider or fake provider not explicitly enabled");
}

const timeoutMs = Number(process.env.AMEME_INFERENCE_TIMEOUT_MS ?? "15000");
const port = Number(process.env.AMEME_INFERENCE_PORT ?? "8787");
const gateway = new InferenceGateway(providerFromEnvironment(), {
  timeoutMs,
  logger: (record) => {
    process.stdout.write(JSON.stringify({ service: "ameme-inference-gateway", ...record }) + "\n");
  },
});
const server = createInferenceServer(gateway);

server.listen(port, "127.0.0.1", () => {
  process.stdout.write(JSON.stringify({ service: "ameme-inference-gateway", status: "listening", port }) + "\n");
});
