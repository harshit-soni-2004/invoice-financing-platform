import { Page, expect, test } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';

/**
 * Registration through the real gateway and auth-service; opt in as for auth.live.spec.ts (IFP_STACK=1,
 * UI_BASE_URL=http://localhost:4200, optional IFP_GATEWAY_URL and IFP_AUTH_URL). Desktop only: the gateway allows 5
 * sign-ups per burst per client address, and this file uses 3.
 */
const GATEWAY = process.env['IFP_GATEWAY_URL'] ?? 'http://localhost:8080';
const AUTH = process.env['IFP_AUTH_URL'] ?? 'http://localhost:9000';
const APP = new URL(process.env['UI_BASE_URL'] ?? 'http://127.0.0.1:4200').origin;

test.skip(process.env['IFP_STACK'] !== '1', 'needs the running stack (IFP_STACK=1)');
test.skip(({ isMobile }) => isMobile, 'registration is rate limited per client address; one viewport is enough');
test.describe.configure({ mode: 'serial' });

const password = randomBytes(18).toString('base64url');
const sellerEmail = `ui-reg-seller-${randomUUID()}@example.test`;

test.beforeEach(async ({ page }) => {
  await page.route('**/config.json', async (route) => {
    const config = await (await route.fetch()).json();
    await route.fulfill({ json: { ...config, apiBaseUrl: GATEWAY } });
  });
});

async function register(page: Page, role: 'Seller' | 'Financier', email: string): Promise<void> {
  await page.goto('/register');
  await page.getByRole('radio', { name: new RegExp(`^${role}`) }).check();
  await page.getByLabel('Full name').fill(`UI ${role}`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByLabel('Confirm password').fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
}

async function signIn(page: Page, email: string): Promise<void> {
  await page.goto('/account');
  await expect(page).toHaveURL(new RegExp(`^${AUTH}/login`));
  await page.locator('input[name="username"]').fill(email);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('a seller registers in the app and can sign in with the new account', async ({ page }) => {
  await register(page, 'Seller', sellerEmail);
  await expect(page.getByRole('heading', { level: 1, name: 'Your seller account is ready' })).toBeVisible();

  await signIn(page, sellerEmail);
  await expect(page).toHaveURL(`${APP}/account`);
  await expect(page.getByText(sellerEmail)).toBeVisible();
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(stored).not.toContain(password);
});

test('registering the same email again is refused as a duplicate', async ({ page }) => {
  await register(page, 'Financier', sellerEmail.toUpperCase());
  await expect(page.getByRole('alert')).toContainText('An account with this email already exists');
});

test('a financier registers, is told about approval, and cannot sign in yet', async ({ page }) => {
  const email = `ui-reg-financier-${randomUUID()}@example.test`;
  await register(page, 'Financier', email);
  await expect(page.getByRole('heading', { level: 1, name: /waiting for approval/ })).toBeVisible();

  await signIn(page, email);
  await expect(page).toHaveURL(new RegExp(`^${AUTH}/login\\?error`));
});
