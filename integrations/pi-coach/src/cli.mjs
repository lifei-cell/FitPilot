import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { createInterface } from "node:readline/promises";
import { createCredentialStore } from "./credentials.mjs";
import { createCompletion, createSubscriptionModels, listAccountModels } from "./provider.mjs";
import { createCoachServer } from "./server.mjs";

const stateDir = resolve(process.env.FITPILOT_PI_STATE_DIR ??
  join(process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state"), "fitpilot", "pi-coach"));
const credentials = createCredentialStore(join(stateDir, "auth.json"));
const models = createSubscriptionModels(credentials);

async function login() {
  const hostId = await credentials.hostId();
  const cancellation = new AbortController();
  const signal = AbortSignal.any([cancellation.signal, AbortSignal.timeout(600_000)]);
  const onInterrupt = () => cancellation.abort();
  process.once("SIGINT", onInterrupt);
  try {
    await models.login("openai", "oauth", {
      signal,
      notify(event) {
        if (event.type === "auth_url") console.log(event.url);
        if (event.type === "progress") console.log(event.message);
      },
      async prompt(prompt) {
        if (!process.stdin.isTTY) {
          return new Promise((_, reject) => {
            prompt.signal.addEventListener("abort", () => reject(prompt.signal.reason), { once: true });
          });
        }
        const input = createInterface({ input: process.stdin, output: process.stdout });
        try {
          return await input.question(`${prompt.message} `, { signal: prompt.signal });
        } finally {
          input.close();
        }
      },
    }, { agentName: "FitPilot", getDeviceId: () => hostId });
    console.log("FitPilot ChatGPT authorization saved.");
  } finally {
    process.removeListener("SIGINT", onInterrupt);
  }
}

async function main(command) {
  switch (command) {
    case "login":
      return login();
    case "logout":
      await models.logout("openai");
      return;
    case "models":
      for (const id of await listAccountModels(models, credentials)) console.log(id);
      return;
    case "serve": {
      const modelId = process.env.FITPILOT_PI_MODEL;
      const host = process.env.FITPILOT_PI_HOST ?? "127.0.0.1";
      const port = Number(process.env.FITPILOT_PI_PORT ?? "4194");
      if (!modelId || !Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error("Set FITPILOT_PI_MODEL and a valid FITPILOT_PI_PORT");
      }
      const complete = createCompletion({ models, credentials, modelId });
      createCoachServer({ bridgeKey: process.env.FITPILOT_PI_BRIDGE_KEY, modelId, complete })
        .listen(port, host, () => console.log(`FitPilot Pi adapter listening on ${host}:${port}`))
        .on("error", (error) => {
          console.error(error.code ?? "Could not start FitPilot Pi adapter");
          process.exitCode = 1;
        });
      return;
    }
    default:
      throw new Error("Use serve, login, models or logout");
  }
}

main(process.argv[2]).catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
