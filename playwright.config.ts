import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/browser', fullyParallel: false, workers: 1,
  use: { baseURL: 'http://127.0.0.1:4320', viewport: { width: 390, height: 844 },
    launchOptions: { executablePath: process.env.CAPTURE_BROWSER_PATH } },
  webServer: { command: 'node --import tsx tests/serve-browser.ts', url: 'http://127.0.0.1:4320/api/capture/status', reuseExistingServer: false,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 5000 } },
});
