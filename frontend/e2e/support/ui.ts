import { Page, expect, TestInfo } from '@playwright/test';
import * as path from 'node:path';

const SCREENSHOT_DIR = path.resolve(__dirname, '..', '..', '..', 'docs', 'screenshots');

/** The page must never scroll sideways: every layout has to fit the viewport, down to phone widths. */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'horizontal overflow in px').toBeLessThanOrEqual(0);
}

export async function setTheme(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.emulateMedia({ colorScheme: theme });
}

/** Writes docs/screenshots/<name>-<project>.png when SCREENSHOTS=1. */
export async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  if (process.env['SCREENSHOTS'] !== '1') {
    return;
  }
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, `${name}-${testInfo.project.name}.png`), fullPage: false });
}
