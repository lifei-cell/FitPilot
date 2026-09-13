import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e-offline",
  workers: 1,
  retries: 0,
  forbidOnly: true,
  use: { baseURL: "http://127.0.0.1:4175", ...devices["Desktop Chrome"], viewport: { width: 390, height: 844 },
    trace: "retain-on-failure", screenshot: "only-on-failure" },
  webServer: { command: "npm run preview -- --host 127.0.0.1 --port 4175 --strictPort",
    url: "http://127.0.0.1:4175", reuseExistingServer: false },
});
