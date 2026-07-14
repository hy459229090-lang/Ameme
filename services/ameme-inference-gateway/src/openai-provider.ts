import { createHmac } from "node:crypto";

import OpenAI from "openai";
import type {
  Response as OpenAIResponse,
  ResponseCreateParamsNonStreaming,
} from "openai/resources/responses/responses";

import { PROVIDER_INTERFACE_VERSION, type DaySummaryRequest, type ProviderResult } from "./contracts.js";
import { GatewayError } from "./errors.js";
import { GENERATED_SUMMARY_JSON_SCHEMA } from "./output-schema.js";
import type { InferenceProvider } from "./provider.js";
import { validateGeneratedSummary } from "./validation.js";

export const DEFAULT_OPENAI_MODEL = "gpt-5.6-luna";

export interface OpenAIProviderConfig {
  apiKey: string;
  safetyHmacKey: string;
  model?: string;
}

export interface ResponsesClient {
  responses: {
    create(
      body: ResponseCreateParamsNonStreaming,
      options?: { signal?: AbortSignal },
    ): Promise<OpenAIResponse>;
  };
}

function safetyIdentifier(subjectRef: string, key: string): string {
  return createHmac("sha256", key).update(subjectRef, "utf8").digest("hex");
}

function hasRefusal(response: OpenAIResponse): boolean {
  return response.output.some(
    (item) => item.type === "message" && item.content.some((content) => content.type === "refusal"),
  );
}

export class OpenAIResponsesProvider implements InferenceProvider {
  private readonly client: ResponsesClient;
  private readonly model: string;
  private readonly safetyHmacKey: string;

  constructor(config: OpenAIProviderConfig, client?: ResponsesClient) {
    if (!config.apiKey || Buffer.byteLength(config.safetyHmacKey, "utf8") < 32) {
      throw new GatewayError("PROVIDER_UNAVAILABLE", { httpStatus: 503 });
    }
    const model = config.model?.trim() || DEFAULT_OPENAI_MODEL;
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(model)) {
      throw new GatewayError("PROVIDER_UNAVAILABLE", { httpStatus: 503 });
    }
    this.model = model;
    this.safetyHmacKey = config.safetyHmacKey;
    this.client = client ?? new OpenAI({ apiKey: config.apiKey, maxRetries: 1 });
  }

  async generate(request: DaySummaryRequest, signal: AbortSignal): Promise<ProviderResult> {
    let response: OpenAIResponse;
    try {
      response = await this.client.responses.create(
        {
          model: this.model,
          store: false,
          safety_identifier: safetyIdentifier(request.subject_ref, this.safetyHmacKey),
          reasoning: { effort: "low" },
          max_output_tokens: request.output_token_budget,
          instructions: [
            "Summarize only the supplied structured event projections for one local date.",
            "Do not invent events, relationships, emotions, causes, or outcomes.",
            "Every summary item must cite one or more event_id values from the input.",
            "Low-confidence candidates must not be stated as confirmed facts.",
            "If there are no events, return empty strings and empty arrays.",
          ].join(" "),
          input: JSON.stringify({
            local_date: request.local_date,
            timezone: request.timezone,
            ledger_revision: request.ledger_revision,
            events: request.events,
          }),
          text: {
            format: {
              type: "json_schema",
              name: "ameme_day_summary_v1",
              strict: true,
              schema: GENERATED_SUMMARY_JSON_SCHEMA,
            },
          },
        },
        { signal },
      );
    } catch (error) {
      if (signal.aborted) throw new GatewayError("PROVIDER_TIMEOUT", { retryable: true, httpStatus: 504 });
      if (error instanceof GatewayError) throw error;
      throw new GatewayError("PROVIDER_UNAVAILABLE", { retryable: true, httpStatus: 503 });
    }
    if (hasRefusal(response)) throw new GatewayError("PROVIDER_REFUSAL", { httpStatus: 422 });
    if (response.status !== "completed") {
      if (response.incomplete_details?.reason === "content_filter") {
        throw new GatewayError("PROVIDER_REFUSAL", { httpStatus: 422 });
      }
      throw new GatewayError("PROVIDER_UNAVAILABLE", { retryable: true, httpStatus: 503 });
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(response.output_text);
    } catch {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
    return {
      output: validateGeneratedSummary(parsed, request.events),
      provider: "openai",
      model: response.model || this.model,
      provider_version: `openai-responses:${PROVIDER_INTERFACE_VERSION}`,
      response_id: response.id,
      output_tokens: response.usage?.output_tokens ?? 0,
    };
  }
}
