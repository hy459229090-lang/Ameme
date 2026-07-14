import type { DaySummaryRequest, ProviderResult } from "./contracts.js";

export interface InferenceProvider {
  generate(request: DaySummaryRequest, signal: AbortSignal): Promise<ProviderResult>;
}
