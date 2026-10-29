import { Page, expect, test } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';

/**
 * The seller workflow against the running auth-service (:9000), gateway and invoice-service, as described in the
 * README. Opt in with IFP_STACK=1 and UI_BASE_URL=http://localhost:4200; IFP_GATEWAY_URL points the app at a gateway on
 * another port. Each run registers a new seller, so it only ever touches that seller's own invoices. Desktop only:
 * registration is rate-limited per client address.
 */
const GATEWAY = process.env['IFP_GATEWAY_URL'] ?? 'http://localhost:8080';
const AUTH = 'http://localhost:9000';
/** A dev-seed invoice owned by the demo seller (invoice-service's `dev` profile); never visible to other sellers. */
const OTHER_SELLERS_INVOICE = 'a0000000-0000-4000-8000-000000000001';

test.skip(process.env['IFP_STACK'] !== '1', 'needs the running stack (IFP_STACK=1)');
test.describe.configure({ mode: 'serial' });

const password = randomBytes(18).toString('base64url');
const email = `ui-invoices-${randomUUID()}@example.test`;
const run = randomUUID().slice(0, 8).toUpperCase();

/** invoice-service's business dates are in Asia/Kolkata. */
function kolkataDate(offsetDays: number): string {
  const date = new Date(Date.now() + offsetDays * 86_400_000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(date);
}

async function useGateway(page: Page): Promise<void> {
  await page.route('**/config.json', async (route) => {
    const config = await (await route.fetch()).json();
    await route.fulfill({ json: { ...config, apiBaseUrl: GATEWAY } });
  });
}

/** Each test starts a new browser context, so auth-service asks for the password once per test. */
async function signIn(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page).toHaveURL(new RegExp(`^${AUTH}/login`));
  await page.locator('input[name="username"]').fill(email);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function createInvoice(page: Page, number: string, dueInDays: number): Promise<void> {
  await page.goto('/seller/invoices/new');
  await expect(page.getByRole('heading', { level: 1, name: 'New invoice' })).toBeVisible();
  await page.getByLabel('Invoice number').fill(number);
  await page.getByLabel('Amount').fill('2,40,000.50');
  await page.getByLabel('Buyer name').fill('UI Check Buyer Pvt Ltd');
  await page.getByLabel('Issue date').fill(kolkataDate(-1));
  await page.getByLabel('Due date').fill(kolkataDate(dueInDays));
  await page.getByRole('button', { name: 'Save as draft' }).click();
  await expect(page.getByRole('status')).toContainText('Invoice saved as a draft.');
}

const statusBadge = (page: Page) => page.locator('.page-header .badge');

test('a new seller creates and submits an invoice through the gateway', async ({ page, request, isMobile }) => {
  test.skip(isMobile, 'registration is rate-limited; one project is enough');
  const registration = await request.post(`${GATEWAY}/api/auth/register`, {
    data: { email, password, displayName: 'UI invoice check', role: 'SELLER' },
  });
  expect(registration.status(), 'registration').toBe(201);

  await useGateway(page);
  await signIn(page, '/seller');

  await expect(page.getByRole('heading', { level: 1, name: 'Your invoices' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'You have no invoices yet' })).toBeVisible();

  await createInvoice(page, `UI-${run}/1`, 60);
  await expect(statusBadge(page)).toHaveText('Draft');
  await page.getByRole('button', { name: 'Submit for verification' }).click();
  await expect(page.getByRole('status')).toContainText('Invoice submitted for verification.');
  await expect(statusBadge(page)).toHaveText('Submitted');

  // Reloading signs in again silently and shows the state invoice-service stored.
  await page.reload();
  await expect(statusBadge(page)).toHaveText('Submitted');

  await page.getByRole('link', { name: '← Back to your invoices' }).click();
  const row = page.getByRole('row').filter({ hasText: `UI-${run}/1` });
  await expect(row).toContainText('Submitted');
  await expect(row).toContainText('₹2,40,000.50');
  await expect(page.getByText('DEMO-')).toHaveCount(0);

  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(stored).not.toMatch(/eyJ[\w-]+\.[\w-]+/);
});

test('invoice-service refuses stale and rule-breaking actions, and other sellers’ invoices', async ({ page, context, isMobile }) => {
  test.skip(isMobile, 'registration is rate-limited; one project is enough');
  await useGateway(page);
  await signIn(page, '/seller');
  await expect(page.getByRole('heading', { level: 1, name: 'Your invoices' })).toBeVisible();

  // Due in 3 days: creating is allowed, submitting is not (at least 7 days to maturity by default).
  await createInvoice(page, `UI-${run}/2`, 3);
  await page.getByRole('button', { name: 'Submit for verification' }).click();
  await expect(page.getByRole('alert')).toContainText('Due date must be at least');
  await expect(statusBadge(page)).toHaveText('Draft');

  // A draft open in this tab is submitted from a second tab; this tab's version is then out of date.
  await createInvoice(page, `UI-${run}/3`, 45);
  const detailsUrl = page.url();
  const other = await context.newPage();
  await useGateway(other);
  // Same browser context: auth-service's session signs the second tab in without the password.
  await other.goto(new URL(detailsUrl).pathname);
  await other.getByRole('button', { name: 'Submit for verification' }).click();
  await expect(other.getByRole('status')).toContainText('Invoice submitted for verification.');
  await other.close();

  await expect(statusBadge(page)).toHaveText('Draft');
  await page.getByRole('button', { name: 'Submit for verification' }).click();
  await expect(page.getByRole('alert')).toContainText('changed since you opened it');
  await expect(statusBadge(page)).toHaveText('Submitted');

  await page.goto(`/seller/invoices/${OTHER_SELLERS_INVOICE}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Invoice not found' })).toBeVisible();
});
