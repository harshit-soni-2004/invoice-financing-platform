import { Route, expect, test } from '@playwright/test';
import { mockAuth } from './support/auth-mock';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

const seller = { role: 'SELLER', name: 'Mintu', email: 'mintu@example.test' } as const;
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
};

/** auth-service's own-account endpoints; registered after mockAuth so they take precedence. */
async function mockAccount(page: import('@playwright/test').Page) {
  const profile = {
    id: '7d4c2f9e-0000-4000-8000-000000000001',
    email: seller.email,
    displayName: seller.name,
    role: seller.role,
    status: 'ACTIVE',
    createdAt: '2026-10-01T09:30:00Z',
  };
  const passwordBodies: unknown[] = [];

  await page.route('http://localhost:8080/api/users/me**', async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    if (path === '/api/users/me' && request.method() === 'PUT') {
      profile.displayName = (request.postDataJSON() as { displayName: string }).displayName;
      return route.fulfill({ headers: CORS, json: profile });
    }
    if (path === '/api/users/me/password' && request.method() === 'POST') {
      const body = request.postDataJSON() as { currentPassword: string };
      passwordBodies.push(body);
      if (body.currentPassword !== 'current-password-1') {
        return route.fulfill({
          status: 400,
          headers: { ...CORS, 'Content-Type': 'application/problem+json' },
          body: JSON.stringify({
            code: 'VALIDATION_FAILED',
            errors: [{ field: 'currentPassword', message: 'is incorrect' }],
          }),
        });
      }
      return route.fulfill({ status: 204, headers: CORS });
    }
    return route.fulfill({ headers: CORS, json: profile });
  });

  return { passwordBodies: () => passwordBodies };
}

test.describe('account settings (mocked auth-service and gateway)', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`a user renames themselves and changes their password (${theme})`, async ({ page, isMobile }, testInfo) => {
      await setTheme(page, theme);
      await mockAuth(page, seller);
      const account = await mockAccount(page);
      await page.goto('/account');

      await expect(page.getByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();
      await expect(page.getByText(seller.email)).toBeVisible();
      await expect(page.getByText('Used to sign in, so it cannot be changed here.')).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await screenshot(page, testInfo, `account-${theme}`);

      const name = page.getByLabel('Display name');
      await expect(name).toHaveValue('Mintu');
      await expect(page.getByRole('button', { name: 'Save name' })).toBeDisabled();
      await name.fill('Mintu Sharma');
      await page.getByRole('button', { name: 'Save name' }).click();
      await expect(page.getByText('Your name was saved.')).toBeVisible();
      if (isMobile) {
        await page.getByRole('button', { name: /^Menu/ }).click();
      }
      await expect(page.getByRole('navigation', { name: 'Primary' }).getByText('Mintu Sharma')).toBeVisible();
      if (isMobile) {
        await page.getByRole('button', { name: /^Menu/ }).click();
      }

      await page.getByLabel('Current password').fill('wrong-password-1');
      await page.getByLabel('New password', { exact: true }).fill('a brand new passphrase');
      await page.getByLabel('Confirm new password').fill('a brand new passphrase');
      await page.getByRole('button', { name: 'Change password' }).click();
      await expect(page.getByText('Your current password is incorrect.')).toBeVisible();

      await page.getByLabel('Current password').fill('current-password-1');
      await page.getByRole('button', { name: 'Change password' }).click();
      await expect(page.getByText('Your password was changed.')).toBeVisible();
      await expect(page.getByLabel('Current password')).toHaveValue('');
      expect(account.passwordBodies()).toEqual([
        { currentPassword: 'wrong-password-1', newPassword: 'a brand new passphrase' },
        { currentPassword: 'current-password-1', newPassword: 'a brand new passphrase' },
      ]);
    });
  }

  test('the theme cards switch the theme', async ({ page }, testInfo) => {
    await setTheme(page, 'light');
    await mockAuth(page, seller);
    await mockAccount(page);
    await page.goto('/account');

    const dark = page.getByRole('button', { name: /^Dark/ });
    await dark.scrollIntoViewIfNeeded();
    await dark.click();
    await expect(dark).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'account-security-and-theme');
  });
});
