import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createCredentialStore } from "../src/credentials.mjs";
import { createCompletion, createSubscriptionModels } from "../src/provider.mjs";
import { createCoachServer } from "../src/server.mjs";

const modelId = "gpt-6.2-sol";
const bridgeKey = "adapter-test-bearer-key-not-for-runtime";
const mockCredential = {
  type: "oauth",
  access: "test-chatgpt-access",
  refresh: "test-chatgpt-refresh",
  expires: Date.now() + 3_600_000,
};
const messages = [
  { role: "system", content: "Return JSON when requested." },
  { role: "user", content: "Suggest two training days." },
];

async function fixture(t, fetchUpstream) {
  const directory = await mkdtemp(join(tmpdir(), "fitpilot-pi-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const credentials = createCredentialStore(join(directory, "auth.json"));
  const models = createSubscriptionModels(credentials);
  const complete = createCompletion({ models, credentials, modelId, fetchUpstream });
  const server = createCoachServer({ bridgeKey, modelId, complete });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise((resolve) => server.close(resolve)));

  const post = (payload, authorization = bridgeKey) =>
    fetch(`http://127.0.0.1:${server.address().port}/v1/chat/completions`, {
      method: "POST",
      headers: { Authorization: `Bearer ${authorization}`, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  return { credentials, post };
}

function stream(events, status = 200) {
  const records = events.map((event) => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
  return new Response(records.join(""), { status, headers: { "Content-Type": "text/event-stream" } });
}

function completedResponse(text) {
  const item = { type: "message", role: "assistant", id: "msg_test", status: "in_progress", content: [] };
  return stream([
    { type: "response.created", response: { id: "resp_test" } },
    { type: "response.output_item.added", output_index: 0, item },
    { type: "response.output_text.delta", output_index: 0, content_index: 0, delta: text },
    { type: "response.output_item.done", output_index: 0, item: {
      ...item, status: "completed", content: [{ type: "output_text", text, annotations: [] }],
    } },
    { type: "response.completed", response: {
      id: "resp_test", status: "completed", output: [],
      usage: { input_tokens: 20, output_tokens: 4, total_tokens: 24, input_tokens_details: { cached_tokens: 8 } },
    } },
  ]);
}

test("translates a FitPilot request through pi-ai Responses and preserves usage", async (t) => {
  const replies = ["卧推前先热身。[1]", '{"daysPerWeek":2}'];
  const observed = [];
  const { credentials, post } = await fixture(t, async (input, init) => {
    const request = new Request(input, init);
    assert.equal(request.url, "https://api.openai.com/v1/responses");
    assert.equal(request.headers.get("authorization"), "Bearer test-chatgpt-access");
    observed.push(await request.json());
    return completedResponse(replies.shift());
  });
  await credentials.modify("openai", async () => mockCredential);
  for (const response_format of [undefined, { type: "json_object" }]) {
    const response = await post({ model: modelId, messages, temperature: 0.2, response_format });
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.choices[0].message.content, !response_format ? "卧推前先热身。[1]" : '{"daysPerWeek":2}');
    assert.deepEqual(body.usage, { prompt_tokens: 20, completion_tokens: 4, total_tokens: 24 });
  }
  for (const request of observed) {
    assert.equal(request.stream, true);
    assert.equal(request.store, false);
    assert.equal(request.temperature, undefined);
    assert.ok(request.input.some(({ role }) => role === "developer"));
    assert.ok(request.input.some(({ role }) => role === "user"));
  }
  assert.equal(observed[0].text?.format, undefined);
  assert.deepEqual(observed[1].text.format, { type: "json_object" });
});

test("requires both the local bridge key and separate ChatGPT authorization", async (t) => {
  let requests = 0;
  const { credentials, post } = await fixture(t, async () => { requests += 1; });
  assert.equal((await post({ model: modelId, messages }, "wrong")).status, 401);
  const unauthorized = await post({ model: modelId, messages });
  assert.equal(unauthorized.status, 401);
  assert.equal((await unauthorized.json()).error.code, "chatgpt_sign_in_required");
  assert.equal((await post({ model: "other-model", messages })).status, 400);
  assert.equal(requests, 0);

  const firstHost = await credentials.hostId();
  await credentials.modify("openai", async () => mockCredential);
  const update = (current) => Promise.resolve({ ...current, expires: current.expires + 1 });
  await Promise.all([credentials.modify("openai", update), credentials.modify("openai", update)]);
  assert.equal((await credentials.read("openai")).expires, mockCredential.expires + 2);
  await credentials.delete("openai");
  assert.equal(await credentials.read("openai"), undefined);
  assert.equal(await credentials.hostId(), firstHost);
});

test("forwards rate limits and rejects failed streams or invalid draft JSON", async (t) => {
  const upstream = [
    new Response('{"error":{"message":"limited"}}', { status: 429, headers: { "Retry-After": "2" } }),
    stream([{ type: "response.failed", response: {
      status: "failed", error: { code: "subscription_sharing_usage_limit_exceeded", message: "limited" },
    } }]),
    completedResponse("This is not JSON"),
    stream([{ type: "response.created", response: { id: "resp_incomplete" } }]),
  ];
  const { credentials, post } = await fixture(t, async () => upstream.shift());
  await credentials.modify("openai", async () => mockCredential);
  const input = { model: modelId, messages, response_format: { type: "json_object" } };
  const first = await post(input);
  assert.equal(first.status, 429);
  assert.equal(first.headers.get("retry-after"), "2");
  assert.equal((await post(input)).status, 429);
  const invalid = await post(input);
  assert.equal(invalid.status, 502);
  assert.equal((await invalid.json()).error.code, "invalid_model_json");
  assert.equal((await post(input)).status, 502);
  assert.equal(upstream.length, 0);
});
