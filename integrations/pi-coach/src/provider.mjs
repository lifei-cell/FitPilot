import { createModels } from "@earendil-works/pi-ai/models";
import { openaiProvider } from "@earendil-works/pi-ai/providers/openai";

export class BridgeError extends Error {
  constructor(status, code, retryAfter) {
    super(code);
    this.status = status;
    this.retryAfter = retryAfter;
  }
}

export function createSubscriptionModels(credentials) {
  const models = createModels({ credentials });
  const provider = openaiProvider();
  models.setProvider({ ...provider, auth: { oauth: provider.auth.oauth } });
  return models;
}

export async function listAccountModels(models, credentials) {
  if ((await credentials.read("openai"))?.type !== "oauth") {
    throw new BridgeError(401, "chatgpt_sign_in_required");
  }
  const auth = await models.getAuth("openai", { signal: AbortSignal.timeout(10_000) });
  if (!auth?.auth.apiKey) throw new BridgeError(401, "chatgpt_sign_in_required");
  const response = await fetch("https://api.openai.com/v1/models", {
    headers: { Authorization: `Bearer ${auth.auth.apiKey}` },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new BridgeError(response.status, "model_list_failed");
  const { models: available } = await response.json();
  return available.filter((item) =>
    item.visibility === "list" && models.getModel("openai", item.slug),
  ).map((item) => item.slug);
}

export function createCompletion({ models, credentials, modelId, fetchUpstream = fetch }) {
  const model = models.getModel("openai", modelId);
  if (!model || model.api !== "openai-responses") {
    throw new Error("FITPILOT_PI_MODEL must name an OpenAI Responses chat model known to pi-ai");
  }

  return async ({ system, user, jsonOutput }, signal) => {
    if ((await credentials.read("openai", { signal }))?.type !== "oauth") {
      throw new BridgeError(401, "chatgpt_sign_in_required");
    }
    let upstreamStatus;
    let retryAfter;
    const result = await models.complete(model, {
      systemPrompt: system,
      messages: [{ role: "user", content: user, timestamp: Date.now() }],
    }, {
      signal,
      cacheRetention: "none",
      maxRetries: 0,
      fetch: async (input, init) => {
        const response = await fetchUpstream(input, init);
        upstreamStatus = response.status;
        retryAfter = response.headers.get("retry-after");
        return response;
      },
      onPayload: jsonOutput ? (payload) => ({
        ...payload,
        text: { ...payload.text, format: { type: "json_object" } },
      }) : undefined,
    });

    if (signal.aborted) throw new BridgeError(504, "model_timeout");
    if (result.stopReason !== "stop") {
      if (upstreamStatus === 429 || /subscription_sharing_\w*usage_limit_exceeded/.test(result.errorMessage ?? "")) {
        throw new BridgeError(429, "chatgpt_usage_limit", retryAfter);
      }
      if (upstreamStatus === 401 || upstreamStatus === 403) {
        throw new BridgeError(upstreamStatus, "chatgpt_authorization_failed");
      }
      throw new BridgeError(502, "model_response_failed");
    }

    const content = result.content.filter((item) => item.type === "text")
      .map((item) => item.text).join("").trim();
    if (!content) throw new BridgeError(502, "empty_model_response");

    if (jsonOutput) {
      let object;
      try { object = JSON.parse(content); } catch { /* rejected below */ }
      if (!object || typeof object !== "object" || Array.isArray(object)) {
        throw new BridgeError(502, "invalid_model_json");
      }
    }

    const { input, cacheRead, cacheWrite, output, totalTokens } = result.usage;
    return {
      choices: [{ message: { role: "assistant", content } }],
      usage: {
        prompt_tokens: input + cacheRead + cacheWrite,
        completion_tokens: output,
        total_tokens: totalTokens,
      },
    };
  };
}
