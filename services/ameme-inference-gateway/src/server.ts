import { createServer, type IncomingMessage, type ServerResponse } from "node:http";

import { LIMITS } from "./contracts.js";
import { asGatewayError, GatewayError } from "./errors.js";
import type { InferenceGateway } from "./gateway.js";

async function readJson(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    bytes += buffer.length;
    if (bytes > LIMITS.maxRequestBytes) throw new GatewayError("INPUT_LIMIT_EXCEEDED", { httpStatus: 413 });
    chunks.push(buffer);
  }
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks));
    return JSON.parse(text);
  } catch {
    throw new GatewayError("INVALID_REQUEST");
  }
}

function send(response: ServerResponse, status: number, value: unknown): void {
  const body = JSON.stringify(value);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
  });
  response.end(body);
}

export function createInferenceServer(gateway: InferenceGateway) {
  return createServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/healthz") {
      send(response, 200, { status: "ok", persistence: "none" });
      return;
    }
    if (request.method !== "POST" || request.url !== "/v1/day-summary") {
      send(response, 404, { error: { code: "NOT_FOUND", retryable: false } });
      return;
    }
    const contentType = request.headers["content-type"]?.split(";", 1)[0]?.trim().toLowerCase();
    if (contentType !== "application/json") {
      send(response, 415, { error: { code: "INVALID_REQUEST", retryable: false } });
      return;
    }
    try {
      const input = await readJson(request);
      const summary = await gateway.summarizeDay(input);
      send(response, 200, { summary });
    } catch (error) {
      const mapped = asGatewayError(error);
      send(response, mapped.httpStatus, { error: { code: mapped.code, retryable: mapped.retryable } });
    }
  });
}
