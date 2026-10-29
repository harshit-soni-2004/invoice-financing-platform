import { Page, expect, test } from '@playwright/test';
import { mockAuth } from './support/auth-mock';
import { expectNoHorizontalOverflow } from './support/ui';

const seller = { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' } as const;

/** At phone widths the account controls sit in the collapsed menu. */
async function openMenu(page: Page, isMobile: boolean): Promise<void> {
  if (isMobile) {
    await page.getByRole('button', { name: 'Menu' }).click();
  }
}

test.describe('sign-in (mocked auth-service and gateway)', () => {
  test('a seller signs in, sees their account and signs out', async ({ page, isMobile }) => {
    const auth = await mockAuth(page, seller);
    await page.goto('/');
    await openMenu(page, isMobile);
    await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Sign in' }).click();

    await expect(page).toHaveURL(/\/seller$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Your invoices' })).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await openMenu(page, isMobile);
    const nav = page.getByRole('navigation', { name: 'Primary' });
    await expect(nav.getByText('Asha Rao')).toBeVisible();
    await expect(nav.getByText('Seller', { exact: true })).toBeVisible();
    await nav.getByRole('link', { name: 'Account' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();
    await expect(page.getByText('asha@example.test')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    // The seller page's invoice list and the account page's profile, both with the one in-memory token.
    expect(new Set(auth.apiAuthorizations())).toEqual(new Set(['Bearer mock-access-1']));
    expect(auth.apiAuthorizations().length).toBeGreaterThanOrEqual(2);

    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain('mock-access');
    expect(stored).not.toContain('ifp.auth');

    await openMenu(page, isMobile);
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/:4200\/$/);
    await openMenu(page, isMobile);
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Sign in' })).toBeVisible();
  });

  test('a guarded page sends the user through sign-in and back', async ({ page }) => {
    const auth = await mockAuth(page, seller);
    await page.goto('/account');
    await expect(page.getByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();
    expect(auth.authorizeCount()).toBe(1);
  });

  test('reloading drops the in-memory token and signs in again without a password', async ({ page }) => {
    const auth = await mockAuth(page, seller);
    await page.goto('/account');
    await expect(page.getByText('asha@example.test')).toBeVisible();

    await page.reload();
    await expect(page.getByText('asha@example.test')).toBeVisible();
    await expect(page).toHaveURL(/\/account$/);
    expect(auth.authorizeCount()).toBe(2);
    expect(auth.apiAuthorizations()).toEqual(['Bearer mock-access-1', 'Bearer mock-access-2']);
  });

  test('another role sees the no-access page', async ({ page }) => {
    await mockAuth(page, seller);
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
    await page.getByRole('link', { name: 'Go to your home page' }).click();
    await expect(page).toHaveURL(/\/seller$/);
  });

  test('a refused sign-in is explained', async ({ page }) => {
    const auth = await mockAuth(page, seller);
    auth.failNextAuthorize('access_denied');
    await page.goto('/account');
    await expect(page.getByRole('alert')).toContainText('Sign-in was cancelled or refused.');
    await expect(page.getByRole('link', { name: 'Start again' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });

  test('a forged callback is rejected', async ({ page }) => {
    await mockAuth(page, seller);
    await page.goto('/auth/callback?code=mock-code-1&state=forged');
    await expect(page.getByRole('alert')).toContainText('no longer valid');
  });
});
