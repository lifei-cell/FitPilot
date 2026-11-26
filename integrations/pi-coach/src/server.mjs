import { timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { BridgeError } from "./provider.mjs";

const maxBodyBytes = 96 * 1024;

function send(response, status, body, retryAfter) {
  const headers = { "Content-Type": "application/json; charset=utf-8" };
  if (retryAfter != null) headers["Retry-After"] = retryAfter;
  if (status === 413) headers.Connection = "close";
  response.writeHead(status, headers);
  response.end(JSON.stringify(body));
}

async function readPrompt(request, modelId) {
  if (Number(request.headers["content-length"]) > maxBodyBytes) {
    throw new BridgeError(413, "request_too_large");
  }
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    bytes += chunk.length;
    if (bytes > maxBodyBytes) throw new BridgeError(413, "request_too_large");
    chunks.push(chunk);
  }
  let body;
  try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch {
    throw new BridgeError(400, "invalid_request_json");
  }
  if (body?.model !== modelId) throw new BridgeError(400, "unknown_model");
  if (body.stream || (body.response_format && body.response_format.type !== "json_object")) {
    throw new BridgeError(400, "unsupported_response_format");
  }
  const [system, user] = Array.isArray(body.messages) ? body.messages : [];
  if (body.messages?.length !== 2 ||
      system?.role !== "system" || typeof system.content !== "string" ||
      user?.role !== "user" || typeof user.content !== "string") {
    throw new BridgeError(400, "invalid_messages");
  }
  return { system: system.content, user: user.content, jsonOutput: Boolean(body.response_format) };
}

export function createCoachServer({ bridgeKey, modelId, complete, timeoutMs = 21000 }) {
  if (typeof bridgeKey !== "string" || bridgeKey.length < 32) {
    throw new Error("FITPILOT_PI_BRIDGE_KEY must contain at least 32 characters");
  }
  const expected = Buffer.from(`Bearer ${bridgeKey}`);
  return createServer({ requestTimeout: timeoutMs }, async (request, response) => {
    if (request.method === "GET" && request.url === "/healthz") {
      return send(response, 200, { status: "UP" });
    }
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
      return send(response, 404, { error: { code: "not_found" } });
    }
    const provided = Buffer.from(request.headers.authorization ?? "");
    if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) {
      return send(response, 401, { error: { code: "unauthorized" } });
    }

    const disconnect = new AbortController();
    response.on("close", () => disconnect.abort());
    const signal = AbortSignal.any([disconnect.signal, AbortSignal.timeout(timeoutMs)]);
    try {
      const prompt = await readPrompt(request, modelId);
      signal.throwIfAborted();
      const completion = await complete(prompt, signal);
      signal.throwIfAborted();
      send(response, 200, completion);
    } catch (error) {
      if (response.destroyed) return;
      const failure = signal.aborted ? new BridgeError(504, "model_timeout") :
        error instanceof BridgeError ? error : new BridgeError(502, "model_gateway_failed");
      send(response, failure.status, { error: { code: failure.message } }, failure.retryAfter);
    }
  });
}
