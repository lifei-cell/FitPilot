import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./server";

const nativeFetch = globalThis.fetch;

beforeAll(() => {
  globalThis.fetch = (input, init) => {
    if (typeof input === "string" && input.startsWith("/")) {
      return nativeFetch(`http://localhost${input}`, init);
    }
    return nativeFetch(input, init);
  };
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  server.resetHandlers();
});

afterAll(() => {
  server.close();
  globalThis.fetch = nativeFetch;
});
