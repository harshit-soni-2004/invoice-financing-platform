import { expect, test } from '@playwright/test';
import { mockNotifications, storedNotification } from './support/admin-mock';
import { mockAuth } from './support/auth-mock';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

const seller = { role: 'SELLER', name: 'Mintu', email: 'mintu@example.test' } as const;

test.describe('dashboard (mocked auth-service and gateway)', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`a signed-in user gets a dashboard instead of the sign-up page (${theme})`, async ({ page, isMobile }, testInfo) => {
      await setTheme(page, theme);
      await mockAuth(page, seller);
      await mockNotifications(page, [storedNotification({ id: 'n1', subject: 'Invoice INV-0001 verified' })]);
      await page.goto('/account');
      await expect(page.getByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();

      if (isMobile) {
        await page.getByRole('button', { name: /^Menu/ }).click();
      }
      await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Home' }).click();

      await expect(page.getByRole('heading', { level: 1, name: /^Good (morning|afternoon|evening), Mintu$/ })).toBeVisible();
      const main = page.locator('main');
      await expect(main.getByRole('link', { name: 'Create an account' })).toHaveCount(0);
      await expect(main.getByRole('link', { name: /^Sign in/ })).toHaveCount(0);
      await expect(main.getByRole('link', { name: /New invoice/ })).toBeVisible();
      await expect(main.getByRole('link', { name: '1 unread notification' })).toBeVisible();

      const buyers = page.getByRole('region', { name: /Potential buyers/ });
      await expect(buyers.getByText('Sample data', { exact: true })).toBeVisible();
      await expect(buyers.getByRole('row')).toHaveCount(9);
      await buyers.getByLabel('Search buyers').fill('Bengaluru');
      await expect(buyers.getByRole('row')).toHaveCount(2);
      await expect(buyers.getByRole('rowheader')).toContainText('Bluepeak Electronics Pvt Ltd');

      await expectNoHorizontalOverflow(page);
      await screenshot(page, testInfo, `dashboard-${theme}`);
    });
  }
});
