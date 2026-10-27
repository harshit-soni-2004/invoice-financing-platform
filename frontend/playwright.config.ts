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
