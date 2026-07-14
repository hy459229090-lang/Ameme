import {
  OUTPUT_SCHEMA_VERSION,
  RULES_VERSION,
  type DaySummary,
  type MetadataLogger,
} from "./contracts.js";
import { asGatewayError, GatewayError } from "./errors.js";
import type { InferenceProvider } from "./provider.js";
import { validateGeneratedSummary, validateRequest } from "./validation.js";

const noOpLogger: MetadataLogger = () => undefined;

function safeLog(logger: MetadataLogger, record: Parameters<MetadataLogger>[0]): void {
  try {
    logger(record);
  } catch {
    // Observability must never expose content or change inference semantics.
  }
}

function validateProviderMetadata(result: {
  provider: unknown;
  model: unknown;
  provider_version: unknown;
  response_id: unknown;
  output_tokens: unknown;
}): void {
  for (const value of [result.provider, result.model, result.provider_version, result.response_id]) {
    if (typeof value !== "string" || value.length === 0 || value.length > 160) {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
  }
  if (!Number.isSafeInteger(result.output_tokens) || (result.output_tokens as number) < 0) {
    throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  }
}

export class InferenceGateway {
  constructor(
    private readonly provider: InferenceProvider,
    private readonly options: { timeoutMs: number; logger?: MetadataLogger },
  ) {
    if (!Number.isSafeInteger(options.timeoutMs) || options.timeoutMs < 100 || options.timeoutMs > 120_000) {
      throw new Error("timeoutMs must be between 100 and 120000");
    }
  }

  async summarizeDay(input: unknown): Promise<DaySummary> {
    const request = validateRequest(input);
    const logger = this.options.logger ?? noOpLogger;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.options.timeoutMs);
    try {
      const result = await this.provider.generate(request, controller.signal);
      validateProviderMetadata(result);
      if (result.output_tokens > request.output_token_budget) {
        throw new GatewayError("OUTPUT_BUDGET_EXCEEDED", { httpStatus: 502 });
      }
      const output = validateGeneratedSummary(result.output, request.events);
      const summary: DaySummary = {
        schema_version: OUTPUT_SCHEMA_VERSION,
        ledger_id: request.ledger_id,
        ledger_revision: request.ledger_revision,
        local_date: request.local_date,
        headline: output.headline,
        overview: output.overview,
        highlights: output.highlights,
        progress: output.progress,
        open_loops: output.open_loops,
        rules_version: RULES_VERSION,
        model: {
          provider: result.provider,
          model_id: result.model,
          provider_version: result.provider_version,
          response_id: result.response_id,
        },
      };
      safeLog(logger, {
        request_id: request.request_id,
        ledger_revision: request.ledger_revision,
        event_count: request.events.length,
        outcome: "ok",
        error_code: null,
      });
      return summary;
    } catch (error) {
      const mapped = controller.signal.aborted
        ? new GatewayError("PROVIDER_TIMEOUT", { retryable: true, httpStatus: 504 })
        : asGatewayError(error);
      safeLog(logger, {
        request_id: request.request_id,
        ledger_revision: request.ledger_revision,
        event_count: request.events.length,
        outcome: "error",
        error_code: mapped.code,
      });
      throw mapped;
    } finally {
      clearTimeout(timer);
    }
  }
}
