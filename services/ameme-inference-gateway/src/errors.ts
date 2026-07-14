export const GATEWAY_ERROR_CODES = [
  "INVALID_REQUEST",
  "DATA_CLASS_DENIED",
  "SENSITIVITY_DENIED",
  "INPUT_LIMIT_EXCEEDED",
  "OUTPUT_BUDGET_INVALID",
  "PROVIDER_TIMEOUT",
  "PROVIDER_REFUSAL",
  "PROVIDER_UNAVAILABLE",
  "PROVIDER_OUTPUT_INVALID",
  "OUTPUT_BUDGET_EXCEEDED",
  "INTERNAL_ERROR",
] as const;

export type GatewayErrorCode = (typeof GATEWAY_ERROR_CODES)[number];

export class GatewayError extends Error {
  readonly code: GatewayErrorCode;
  readonly retryable: boolean;
  readonly httpStatus: number;

  constructor(code: GatewayErrorCode, options?: { retryable?: boolean; httpStatus?: number }) {
    super(code);
    this.name = "GatewayError";
    this.code = code;
    this.retryable = options?.retryable ?? false;
    this.httpStatus = options?.httpStatus ?? 400;
  }
}

export function asGatewayError(error: unknown): GatewayError {
  if (error instanceof GatewayError) return error;
  return new GatewayError("INTERNAL_ERROR", { httpStatus: 500 });
}
