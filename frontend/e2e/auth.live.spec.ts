import { APIRequestContext, Page, expect, test } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';

/**
 * Real sign-in against a running auth-service (:9000) and gateway, as described in the README. Opt in with
 * IFP_STACK=1 and UI_BASE_URL=http://localhost:4200: auth-service only accepts the registered localhost callback.
 * IFP_GATEWAY_URL points the app at a gateway on another port (the app's config.json is overridden in the browser).
 */
const GATEWAY = process.env['IFP_GATEWAY_URL'] ?? 'http://localhost:8080';
const AUTH = 'http://localhost:9000';

test.skip(process.env['IFP_STACK'] !== '1', 'needs the running stack (IFP_STACK=1)');
test.describe.configure({ mode: 'serial' });

test.beforeEach(async ({ page }) => {
  await page.route('**/config.json', async (route) => {
    const config = await (await route.fetch()).json();
    await route.fulfill({ json: { ...config, apiBaseUrl: GATEWAY } });
  });
});

const password = randomBytes(18).toString('base64url');

async function register(request: APIRequestContext, role: 'SELLER' | 'FINANCIER'): Promise<string> {
  const email = `ui-${role.toLowerCase()}-${randomUUID()}@example.test`;
  const response = await request.post(`${GATEWAY}/api/auth/register`, {
    data: { email, password, displayName: `UI ${role.toLowerCase()}`, role },
  });
  expect(response.status(), 'registration').toBe(201);
  return email;
}

async function signInOnAuthService(page: Page, email: string): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`^${AUTH}/login`));
  await page.locator('input[name="username"]').fill(email);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('a seller signs in through auth-service, loads their profile through the gateway and signs out', async ({
  page,
  request,
  isMobile,
}) => {
  const email = await register(request, 'SELLER');

  await page.goto('/account');
  await signInOnAuthService(page, email);

  await expect(page).toHaveURL(/:4200\/account$/);
  await expect(page.getByText(email)).toBeVisible();
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(stored).not.toMatch(/eyJ[\w-]+\.[\w-]+/);

  await page.goto('/seller');
  await expect(page).toHaveURL(/:4200\/seller$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Seller workspace' })).toBeVisible();

  if (isMobile) {
    await page.getByRole('button', { name: 'Menu' }).click();
  }
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/:4200\/$/);

  // The auth-service session ended too, so the password is required again.
  await page.goto('/account');
  await expect(page).toHaveURL(new RegExp(`^${AUTH}/login`));
});

test('a financier awaiting approval cannot sign in', async ({ page, request }) => {
  const email = await register(request, 'FINANCIER');

  await page.goto('/account');
  await signInOnAuthService(page, email);

  await expect(page).toHaveURL(new RegExp(`^${AUTH}/login\\?error`));
});
