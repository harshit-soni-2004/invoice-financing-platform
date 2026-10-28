import { Page, Request, Route, expect, test } from '@playwright/test';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

/** Must match public/config.json. */
const REGISTER_URL = 'http://localhost:8080/api/auth/register';
const PASSWORD = 'violet harbour lantern';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Expose-Headers': 'Retry-After',
};

type Reply = (route: Route, body: Record<string, unknown>) => Promise<void>;

/** Stands in for the gateway's registration endpoint and records what the app sent. */
async function mockRegistration(page: Page, reply: Reply): Promise<Request[]> {
  const requests: Request[] = [];
  await page.route(REGISTER_URL, async (route) => {
    if (route.request().method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    requests.push(route.request());
    return reply(route, route.request().postDataJSON() as Record<string, unknown>);
  });
  return requests;
}

const created: Reply = (route, body) =>
  route.fulfill({
    status: 201,
    headers: CORS,
    json: {
      id: '3f6c1a9e-0000-4000-8000-000000000002',
      email: String(body['email']).toLowerCase(),
      displayName: body['displayName'],
      role: body['role'],
      status: body['role'] === 'FINANCIER' ? 'PENDING_APPROVAL' : 'ACTIVE',
      createdAt: '2026-10-28T09:00:00Z',
    },
  });

async function fillForm(page: Page, role: 'Seller' | 'Financier', email = 'asha@example.test'): Promise<void> {
  await page.getByRole('radio', { name: new RegExp(`^${role}`) }).check();
  await page.getByLabel('Full name').fill('Asha Rao');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
}

test.describe('registration (mocked gateway)', () => {
  test('the landing page leads to the form, which fits the viewport', async ({ page }, testInfo) => {
    await setTheme(page, 'light');
    await page.goto('/');
    await page.getByRole('link', { name: 'Create an account' }).click();

    await expect(page).toHaveURL(/\/register$/);
    await expect(page).toHaveTitle(/Create an account/);
    await expect(page.getByRole('heading', { level: 1, name: 'Create your account' })).toBeVisible();
    await expect(page.getByRole('radio')).toHaveCount(2);
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'register');
  });

  test('the form works from the keyboard and reports problems next to each field', async ({ page }) => {
    const requests = await mockRegistration(page, created);
    await page.goto('/register');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByText('Choose whether you are a seller or a financier.')).toBeVisible();
    await expect(page.getByText('Enter your email address.')).toBeVisible();
    await expect(page.getByRole('radio', { name: /^Seller/ })).toBeFocused();
    await expectNoHorizontalOverflow(page);

    await page.keyboard.press('Space');
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Full name')).toBeFocused();
    await page.keyboard.type('Asha Rao');
    await page.keyboard.press('Tab');
    await page.keyboard.type('asha@example');
    await page.keyboard.press('Tab');
    await page.keyboard.type('short');
    await page.keyboard.press('Tab');
    await page.keyboard.type('other');
    await page.keyboard.press('Enter');
    await expect(page.getByText('Password must be at least 12 characters.')).toBeVisible();
    await expect(page.getByText('The passwords do not match.')).toBeVisible();
    expect(requests).toHaveLength(0);
  });

  test('a seller registers and is pointed to sign-in, with nothing kept in the browser', async ({ page }) => {
    const requests = await mockRegistration(page, created);
    await page.goto('/register');
    await fillForm(page, 'Seller', '  Asha@Example.test ');
    await page.getByRole('button', { name: 'Create account' }).click();

    const heading = page.getByRole('heading', { level: 1, name: 'Your seller account is ready' });
    await expect(heading).toBeFocused();
    await expect(page.getByText('asha@example.test')).toBeVisible();
    await expect(page.getByRole('main').getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
    await expectNoHorizontalOverflow(page);

    expect(requests).toHaveLength(1);
    expect(requests[0].postDataJSON()).toEqual({
      role: 'SELLER',
      displayName: 'Asha Rao',
      email: 'Asha@Example.test',
      password: PASSWORD,
    });
    expect(await requests[0].headerValue('authorization')).toBeNull();
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain(PASSWORD);
  });

  test('a financier learns the account waits for approval', async ({ page }) => {
    await mockRegistration(page, created);
    await page.goto('/register');
    await page.getByRole('radio', { name: /^Financier/ }).check();
    await expect(page.getByText('Financier accounts start as pending.')).toBeVisible();
    await fillForm(page, 'Financier');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByRole('heading', { level: 1, name: /waiting for approval/ })).toBeFocused();
    await expect(page.getByText('until then, sign-in is refused')).toBeVisible();
  });

  test('a duplicate email is explained, with a way to sign in', async ({ page }) => {
    await mockRegistration(page, (route) =>
      route.fulfill({ status: 409, headers: CORS, json: { code: 'DUPLICATE_EMAIL', detail: 'exists' } }),
    );
    await page.goto('/register');
    await fillForm(page, 'Seller');
    await page.getByRole('button', { name: 'Create account' }).click();

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('An account with this email already exists');
    await expect(alert).toBeFocused();
    await expect(alert.getByRole('link', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByLabel('Email')).toHaveAttribute('aria-invalid', 'true');
  });

  test('rate limiting and network failures leave the form ready to retry', async ({ page }) => {
    let attempt = 0;
    await mockRegistration(page, async (route, body) => {
      attempt++;
      if (attempt === 1) {
        return route.fulfill({ status: 429, headers: { ...CORS, 'Retry-After': '12' } });
      }
      if (attempt === 2) {
        return route.abort('connectionrefused');
      }
      return created(route, body);
    });
    await page.goto('/register');
    await fillForm(page, 'Seller');
    const submit = page.getByRole('button', { name: 'Create account' });

    await submit.click();
    await expect(page.getByRole('alert')).toContainText('Wait 12 seconds');
    await submit.click();
    await expect(page.getByRole('alert')).toContainText('could not be reached');
    await submit.click();
    await expect(page.getByRole('heading', { level: 1, name: 'Your seller account is ready' })).toBeVisible();
  });

  test('a second click while the request is in flight sends nothing more', async ({ page }) => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    const requests = await mockRegistration(page, async (route, body) => {
      await held;
      return created(route, body);
    });
    await page.goto('/register');
    await fillForm(page, 'Seller');
    await page.getByRole('button', { name: 'Create account' }).click();

    const busy = page.getByRole('button', { name: 'Creating account…' });
    await expect(busy).toBeDisabled();
    await page.getByLabel('Confirm password').press('Enter');
    release();
    await expect(page.getByRole('heading', { level: 1, name: 'Your seller account is ready' })).toBeVisible();
    expect(requests).toHaveLength(1);
  });
});
