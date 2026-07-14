export const INPUT_SCHEMA_VERSION = "ameme.day-event-projection.v1";
export const OUTPUT_SCHEMA_VERSION = "ameme.day-summary.v1";
export const RULES_VERSION = "ameme.day-summary-rules.v1";
export const PROVIDER_INTERFACE_VERSION = "ameme.inference-provider.v1";

export const LIMITS = {
  maxRequestBytes: 65_536,
  maxEvents: 100,
  maxTitleCodePoints: 200,
  maxDetailCodePoints: 1_000,
  minOutputTokens: 256,
  maxOutputTokens: 1_200,
  defaultOutputTokens: 800,
} as const;

export type AllowedSensitivity = "public" | "personal" | "confidential";

export interface StructuredEventProjection {
  event_id: string;
  revision: number;
  local_date: string;
  event_time: string;
  event_type:
    | "activity"
    | "communication"
    | "decision"
    | "result"
    | "state_change"
    | "milestone"
    | "experience";
  title: string;
  detail: string;
  fact_status: "confirmed" | "user_asserted" | "low_confidence_candidate";
  evidence_state: "observed" | "user_asserted" | "inferred";
  sensitivity: AllowedSensitivity;
  importance: number;
}

export interface DaySummaryRequest {
  schema_version: typeof INPUT_SCHEMA_VERSION;
  request_id: string;
  subject_ref: string;
  data_class: "structured";
  ledger_id: string;
  ledger_revision: number;
  local_date: string;
  timezone: string;
  events: StructuredEventProjection[];
  output_token_budget: number;
}

export interface SummaryItem {
  text: string;
  event_ids: string[];
}

export interface GeneratedDaySummary {
  headline: string;
  overview: string;
  highlights: SummaryItem[];
  progress: SummaryItem[];
  open_loops: SummaryItem[];
}

export interface ProviderResult {
  output: GeneratedDaySummary;
  provider: string;
  model: string;
  provider_version: string;
  response_id: string;
  output_tokens: number;
}

export interface DaySummary {
  schema_version: typeof OUTPUT_SCHEMA_VERSION;
  ledger_id: string;
  ledger_revision: number;
  local_date: string;
  headline: string;
  overview: string;
  highlights: SummaryItem[];
  progress: SummaryItem[];
  open_loops: SummaryItem[];
  rules_version: typeof RULES_VERSION;
  model: {
    provider: string;
    model_id: string;
    provider_version: string;
    response_id: string;
  };
}

export interface RequestLogRecord {
  request_id: string;
  ledger_revision: number;
  event_count: number;
  outcome: "ok" | "error";
  error_code: string | null;
}

export type MetadataLogger = (record: RequestLogRecord) => void;
