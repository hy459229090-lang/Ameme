import {
  INPUT_SCHEMA_VERSION,
  LIMITS,
  type DaySummaryRequest,
  type GeneratedDaySummary,
  type StructuredEventProjection,
  type SummaryItem,
} from "./contracts.js";
import { GatewayError } from "./errors.js";

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const LOCAL_DATE = /^\d{4}-\d{2}-\d{2}$/;
const TIMESTAMP_WITH_ZONE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/;
const TIMEZONE = /^[A-Za-z_]+(?:\/[A-Za-z0-9_+.-]+)+$|^UTC$/;
const EVENT_TYPES = new Set([
  "activity",
  "communication",
  "decision",
  "result",
  "state_change",
  "milestone",
  "experience",
]);
const FACT_STATUS = new Set(["confirmed", "user_asserted", "low_confidence_candidate", "planned"]);
const EVIDENCE_STATE = new Set(["observed", "user_asserted", "inferred"]);
const ALLOWED_SENSITIVITY = new Set(["public", "personal", "confidential"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(value: Record<string, unknown>, required: readonly string[]): void {
  const actual = Object.keys(value).sort();
  const expected = [...required].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    throw new GatewayError("INVALID_REQUEST");
  }
}

function codePoints(value: string): number {
  return [...value].length;
}

function requireId(value: unknown): string {
  if (typeof value !== "string" || !ID.test(value)) throw new GatewayError("INVALID_REQUEST");
  return value;
}

function requireInteger(value: unknown, minimum = 1): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum) {
    throw new GatewayError("INVALID_REQUEST");
  }
  return value as number;
}

function requireString(value: unknown, maximum: number, allowEmpty = false): string {
  if (typeof value !== "string" || (!allowEmpty && value.length === 0) || codePoints(value) > maximum) {
    throw new GatewayError("INVALID_REQUEST");
  }
  return value;
}

function validateEvent(value: unknown, localDate: string): StructuredEventProjection {
  if (!isRecord(value)) throw new GatewayError("INVALID_REQUEST");
  exactKeys(value, [
    "event_id",
    "revision",
    "local_date",
    "event_time",
    "event_type",
    "title",
    "detail",
    "fact_status",
    "evidence_state",
    "sensitivity",
    "importance",
  ]);
  if (value.local_date !== localDate || typeof value.local_date !== "string") {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (
    value.event_time !== null &&
    (typeof value.event_time !== "string" || !TIMESTAMP_WITH_ZONE.test(value.event_time))
  ) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (typeof value.event_type !== "string" || !EVENT_TYPES.has(value.event_type)) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (typeof value.fact_status !== "string" || !FACT_STATUS.has(value.fact_status)) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (typeof value.evidence_state !== "string" || !EVIDENCE_STATE.has(value.evidence_state)) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (value.sensitivity === "restricted") throw new GatewayError("SENSITIVITY_DENIED");
  if (typeof value.sensitivity !== "string" || !ALLOWED_SENSITIVITY.has(value.sensitivity)) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (typeof value.importance !== "number" || !Number.isFinite(value.importance) || value.importance < 0 || value.importance > 1) {
    throw new GatewayError("INVALID_REQUEST");
  }
  return {
    event_id: requireId(value.event_id),
    revision: requireInteger(value.revision),
    local_date: value.local_date,
    event_time: value.event_time as string | null,
    event_type: value.event_type as StructuredEventProjection["event_type"],
    title: requireString(value.title, LIMITS.maxTitleCodePoints),
    detail: requireString(value.detail, LIMITS.maxDetailCodePoints, true),
    fact_status: value.fact_status as StructuredEventProjection["fact_status"],
    evidence_state: value.evidence_state as StructuredEventProjection["evidence_state"],
    sensitivity: value.sensitivity as StructuredEventProjection["sensitivity"],
    importance: value.importance,
  };
}

export function validateRequest(value: unknown): DaySummaryRequest {
  let bytes: number;
  try {
    bytes = Buffer.byteLength(JSON.stringify(value), "utf8");
  } catch {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (bytes > LIMITS.maxRequestBytes) throw new GatewayError("INPUT_LIMIT_EXCEEDED");
  if (!isRecord(value)) throw new GatewayError("INVALID_REQUEST");
  exactKeys(value, [
    "schema_version",
    "request_id",
    "subject_ref",
    "data_class",
    "ledger_id",
    "ledger_revision",
    "local_date",
    "timezone",
    "events",
    "output_token_budget",
  ]);
  if (value.schema_version !== INPUT_SCHEMA_VERSION) throw new GatewayError("INVALID_REQUEST");
  if (value.data_class !== "structured") throw new GatewayError("DATA_CLASS_DENIED");
  if (typeof value.local_date !== "string" || !LOCAL_DATE.test(value.local_date)) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (typeof value.timezone !== "string" || !TIMEZONE.test(value.timezone)) {
    throw new GatewayError("INVALID_REQUEST");
  }
  if (!Array.isArray(value.events)) throw new GatewayError("INVALID_REQUEST");
  if (value.events.length > LIMITS.maxEvents) throw new GatewayError("INPUT_LIMIT_EXCEEDED");
  if (!Number.isSafeInteger(value.output_token_budget)) throw new GatewayError("OUTPUT_BUDGET_INVALID");
  const budget = value.output_token_budget as number;
  if (budget < LIMITS.minOutputTokens || budget > LIMITS.maxOutputTokens) {
    throw new GatewayError("OUTPUT_BUDGET_INVALID");
  }
  const events = value.events.map((event) => validateEvent(event, value.local_date as string));
  return {
    schema_version: INPUT_SCHEMA_VERSION,
    request_id: requireId(value.request_id),
    subject_ref: requireId(value.subject_ref),
    data_class: "structured",
    ledger_id: requireId(value.ledger_id),
    ledger_revision: requireInteger(value.ledger_revision),
    local_date: value.local_date,
    timezone: value.timezone,
    events,
    output_token_budget: budget,
  };
}

function validateSummaryItems(value: unknown, knownIds: Set<string>): SummaryItem[] {
  if (!Array.isArray(value) || value.length > 8) throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  return value.map((item) => {
    if (!isRecord(item)) throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    try {
      exactKeys(item, ["text", "event_ids"]);
    } catch {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
    if (typeof item.text !== "string" || item.text.length === 0 || codePoints(item.text) > 240) {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
    if (!Array.isArray(item.event_ids) || item.event_ids.length === 0 || item.event_ids.length > 8) {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
    const eventIds = item.event_ids.map((id) => {
      if (typeof id !== "string" || !knownIds.has(id)) {
        throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
      }
      return id;
    });
    if (new Set(eventIds).size !== eventIds.length) {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
    return { text: item.text, event_ids: eventIds };
  });
}

export function validateGeneratedSummary(value: unknown, events: StructuredEventProjection[]): GeneratedDaySummary {
  if (!isRecord(value)) throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  try {
    exactKeys(value, ["headline", "overview", "highlights", "progress", "open_loops"]);
  } catch {
    throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  }
  if (typeof value.headline !== "string" || codePoints(value.headline) > 80) {
    throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  }
  if (typeof value.overview !== "string" || codePoints(value.overview) > 800) {
    throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  }
  const knownIds = new Set(events.map((event) => event.event_id));
  if (events.length === 0) {
    if (value.headline !== "" || value.overview !== "") {
      throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
    }
    for (const key of ["highlights", "progress", "open_loops"] as const) {
      if (!Array.isArray(value[key]) || value[key].length !== 0) {
        throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
      }
    }
  }
  const highlights = validateSummaryItems(value.highlights, knownIds);
  const progress = validateSummaryItems(value.progress, knownIds);
  const openLoops = validateSummaryItems(value.open_loops, knownIds);
  const plannedIds = new Set(
    events.filter((event) => event.fact_status === "planned").map((event) => event.event_id),
  );
  if (
    [...highlights, ...progress].some((item) => item.event_ids.some((eventId) => plannedIds.has(eventId)))
  ) {
    throw new GatewayError("PROVIDER_OUTPUT_INVALID", { httpStatus: 502 });
  }
  return {
    headline: value.headline,
    overview: value.overview,
    highlights,
    progress,
    open_loops: openLoops,
  };
}
