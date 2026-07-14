import { createHash } from "node:crypto";

import { PROVIDER_INTERFACE_VERSION, type DaySummaryRequest, type ProviderResult, type SummaryItem } from "./contracts.js";
import type { InferenceProvider } from "./provider.js";

function item(event: DaySummaryRequest["events"][number]): SummaryItem {
  return { text: event.title, event_ids: [event.event_id] };
}

export class DeterministicFakeProvider implements InferenceProvider {
  async generate(request: DaySummaryRequest, signal: AbortSignal): Promise<ProviderResult> {
    signal.throwIfAborted();
    const ranked = [...request.events].sort(
      (left, right) => right.importance - left.importance || left.event_id.localeCompare(right.event_id),
    );
    const highlights = ranked.slice(0, 3).map(item);
    const progress = ranked
      .filter((event) => event.event_type === "result" || event.event_type === "milestone")
      .slice(0, 3)
      .map(item);
    const openLoops = ranked
      .filter((event) => event.event_type === "decision" || event.fact_status === "low_confidence_candidate")
      .slice(0, 3)
      .map(item);
    const output = request.events.length === 0
      ? { headline: "", overview: "", highlights: [], progress: [], open_loops: [] }
      : {
          headline: `${request.local_date} · ${request.events.length} events`,
          overview: `The structured ledger contains ${request.events.length} events.`,
          highlights,
          progress,
          open_loops: openLoops,
        };
    const responseId = createHash("sha256")
      .update(JSON.stringify({ ledger: request.ledger_id, revision: request.ledger_revision, output }))
      .digest("hex")
      .slice(0, 24);
    return {
      output,
      provider: "deterministic_fake",
      model: "ameme-fake-summary-v1",
      provider_version: PROVIDER_INTERFACE_VERSION,
      response_id: `fake_${responseId}`,
      output_tokens: Math.max(1, Math.ceil(JSON.stringify(output).length / 4)),
    };
  }
}
