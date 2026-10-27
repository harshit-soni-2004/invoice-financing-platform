import { expect, test } from '@playwright/test';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

test.describe('app shell', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`landing page fits the viewport (${theme})`, async ({ page }, testInfo) => {
      await setTheme(page, theme);
      await page.goto('/');
      await expect(page.getByRole('heading', { level: 1, name: /working capital/i })).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await screenshot(page, testInfo, `landing-${theme}`);
    });
  }

  test('the first Tab reaches the skip link, which moves focus to the main content', async ({ page }) => {
    await page.goto('/');
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to main content' });
    await expect(skip).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.locator('main#main')).toBeFocused();
  });

  test('unknown routes show the not-found page', async ({ page }) => {
    await page.goto('/no/such/page');
    await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });
});

test.describe('mobile menu', () => {
  test.skip(({ isMobile }) => !isMobile, 'only shown at narrow widths');

  test('opens and closes by keyboard and reports its state', async ({ page }) => {
    await page.goto('/');
    const toggle = page.getByRole('button', { name: 'Menu' });
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Home' })).toBeVisible();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });
});
