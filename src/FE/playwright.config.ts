import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  timeout: 45000,
  expect: { timeout: 12000 },
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.FE_TEST_URL || "http://127.0.0.1:5173",
    channel: "msedge",
    viewport: { width: 1440, height: 960 },
    locale: "vi-VN",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    reducedMotion: "reduce",
  },
});
