import { defineConfig, devices } from '@playwright/test';

/**
 * UI checks run against `ng serve` with the locally installed Chrome (channel "chrome"), so no browser download is
 * needed. Set SCREENSHOTS=1 to also write README screenshots to ../docs/screenshots.
 */
const baseURL = process.env['UI_BASE_URL'] ?? 'http://127.0.0.1:4200';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: [['list']],
  // A sign-in redirect loads the unbundled dev-server app twice; with several browsers in parallel that alone can take
  // longer than the 5 s default.
  expect: { timeout: 15_000 },
  timeout: 60_000,
  use: {
    baseURL,
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], channel: 'chrome', viewport: { width: 1280, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], channel: 'chrome' } },
  ],
  webServer: process.env['UI_BASE_URL']
    ? undefined
    : {
        command: 'npx ng serve --port 4200 --host 127.0.0.1',
        url: baseURL,
        reuseExistingServer: true,
        timeout: 180_000,
      },
});
