import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import lockfile from "proper-lockfile";

// Pi refreshes OAuth credentials inside modify(). A file lock also serializes
// refreshes against the separately invoked login and logout commands.
export function createCredentialStore(file) {
  async function readState() {
    try {
      return JSON.parse(await readFile(file, "utf8"));
    } catch (error) {
      if (error.code === "ENOENT") return {};
      throw error;
    }
  }

  async function saveState(state) {
    const temporary = `${file}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporary, JSON.stringify(state), { flag: "wx", mode: 0o600 });
      await rename(temporary, file);
    } finally {
      await rm(temporary, { force: true });
    }
  }

  async function withLock(operation, signal) {
    signal?.throwIfAborted();
    await mkdir(dirname(file), { recursive: true, mode: 0o700 });
    const release = await lockfile.lock(file, {
      realpath: false,
      retries: { retries: 8, factor: 1.5, minTimeout: 30, maxTimeout: 450 },
    });
    try {
      signal?.throwIfAborted();
      return await operation(await readState());
    } finally {
      await release();
    }
  }

  function requireOpenAi(providerId) {
    if (providerId !== "openai") throw new Error("Unknown credential provider");
  }

  return {
    async hostId() {
      return withLock(async (state) => {
        if (state.hostId) return state.hostId;
        const hostId = randomUUID();
        await saveState({ ...state, hostId });
        return hostId;
      });
    },
    async read(providerId, { signal } = {}) {
      signal?.throwIfAborted();
      return providerId === "openai" ? (await readState()).credential : undefined;
    },
    async list({ signal } = {}) {
      signal?.throwIfAborted();
      const { credential } = await readState();
      return credential ? [{ providerId: "openai", type: credential.type }] : [];
    },
    async modify(providerId, update, { signal } = {}) {
      requireOpenAi(providerId);
      return withLock(async (state) => {
        const credential = await update(state.credential);
        if (credential === undefined) return state.credential;
        if (credential.type !== "oauth") throw new Error("ChatGPT authorization is required");
        await saveState({ ...state, credential });
        return credential;
      }, signal);
    },
    async delete(providerId, { signal } = {}) {
      requireOpenAi(providerId);
      await withLock(async ({ hostId }) => saveState({ hostId }), signal);
    },
  };
}
