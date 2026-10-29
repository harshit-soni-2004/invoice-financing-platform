import { Page, expect, test } from '@playwright/test';
import { mockAuth } from './support/auth-mock';
import { mockFinancing } from './support/financing-mock';
import { mockInvoices, storedInvoice } from './support/invoice-mock';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

const seller = { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' } as const;

function isoDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

async function fillInvoice(page: Page, number: string): Promise<void> {
  await page.getByLabel('Invoice number').fill(number);
  await page.getByLabel('Amount').fill('2,40,000.50');
  await page.getByLabel('Buyer name').fill('Kaveri Textiles Pvt Ltd');
  await page.getByLabel('Buyer GSTIN').fill('27aapfu0939f1zv');
  await page.getByLabel('Issue date').fill(isoDate(-3));
  await page.getByLabel('Due date').fill(isoDate(60));
  await page.getByLabel('Description').fill('Cotton fabric rolls');
}

const statusBadge = (page: Page) => page.locator('.page-header .badge');

test.describe('seller invoices (mocked auth-service and gateway)', () => {
  test('a seller adds an invoice, submits it and finds it in the list', async ({ page }, testInfo) => {
    await setTheme(page, 'light');
    await mockAuth(page, seller);
    const api = await mockInvoices(page);

    await page.goto('/seller');
    await expect(page.getByRole('heading', { level: 1, name: 'Your invoices' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'You have no invoices yet' })).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await page.getByRole('link', { name: 'Add your first invoice' }).click();
    await expect(page).toHaveURL(/\/seller\/invoices\/new$/);
    await expect(page.getByRole('heading', { level: 1, name: 'New invoice' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await fillInvoice(page, 'INV-E2E/001');

    // A double click while the request is in flight still creates one invoice.
    api.setDelay(400);
    await page.getByRole('button', { name: 'Save as draft' }).dblclick();
    await expect(page.getByRole('button', { name: 'Saving invoice…' })).toBeDisabled();
    await expect(page).toHaveURL(/\/seller\/invoices\/c0000000-/);
    api.setDelay(0);
    const creates = api.requests.filter((r) => r.method() === 'POST' && r.url().endsWith('/api/invoices'));
    expect(creates).toHaveLength(1);
    expect(creates[0].postDataJSON()).toEqual({
      invoiceNumber: 'INV-E2E/001',
      buyerName: 'Kaveri Textiles Pvt Ltd',
      buyerGstin: '27AAPFU0939F1ZV',
      description: 'Cotton fabric rolls',
      amount: '240000.50',
      issueDate: isoDate(-3),
      dueDate: isoDate(60),
    });

    await expect(page.getByRole('status')).toContainText('Invoice saved as a draft.');
    await expect(statusBadge(page)).toHaveText('Draft');
    await expect(page.getByRole('button', { name: 'Make available to financiers' })).toBeDisabled();
    await expectNoHorizontalOverflow(page);

    await page.getByRole('button', { name: 'Submit for verification' }).click();
    await expect(page.getByRole('status')).toContainText('Invoice submitted for verification.');
    await expect(statusBadge(page)).toHaveText('Submitted');
    await expect(page.getByRole('button', { name: 'Submit for verification' })).toBeDisabled();
    const submit = api.requests.find((r) => r.url().endsWith('/submit'))!;
    expect(await submit.headerValue('if-match')).toBe('"0"');

    await page.getByRole('link', { name: '← Back to your invoices' }).click();
    const row = page.getByRole('row').filter({ hasText: 'INV-E2E/001' });
    await expect(row).toContainText('Submitted');
    await expect(row).toContainText('₹2,40,000.50');
    await expect(page.getByText('Showing 1–1 of 1 invoice')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'seller-invoices');

    for (const request of api.requests) {
      expect(request.url()).not.toContain('sellerId');
    }
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain('mock-access');
  });

  test('the form explains problems, from the browser and from invoice-service', async ({ page }) => {
    await mockAuth(page, seller);
    const api = await mockInvoices(page);
    await page.goto('/seller/invoices/new');
    await expect(page.getByRole('heading', { level: 1, name: 'New invoice' })).toBeVisible();

    await page.getByRole('button', { name: 'Save as draft' }).click();
    await expect(page.getByText('Enter the invoice number.')).toBeVisible();
    await expect(page.getByText('Enter the due date.')).toBeVisible();
    await expect(page.getByLabel('Invoice number')).toBeFocused();
    expect(api.requests).toHaveLength(0);

    await fillInvoice(page, 'INV-E2E/002');
    api.failNext('POST', 422, { code: 'DUE_DATE_TOO_SOON', detail: 'Due date must be at least 7 days away' });
    await page.getByRole('button', { name: 'Save as draft' }).click();
    await expect(page.getByRole('alert')).toContainText('Due date must be at least 7 days away');
    await expect(page.getByLabel('Due date')).toBeFocused();
    await expect(page.getByLabel('Due date')).toHaveAttribute('aria-invalid', 'true');
    await expect(page).toHaveURL(/\/seller\/invoices\/new$/);
    await expectNoHorizontalOverflow(page);
  });

  test('an action on out-of-date details is refused and the latest state is shown', async ({ page }) => {
    await mockAuth(page, seller);
    const api = await mockInvoices(page, [storedInvoice({})]);
    await page.goto('/seller/invoices/b0000000-0000-4000-8000-000000000001');
    await expect(statusBadge(page)).toHaveText('Draft');

    // Meanwhile the invoice is cancelled elsewhere, which bumps its version.
    Object.assign(api.invoices[0], { status: 'CANCELLED', statusReason: 'Order withdrawn', version: 1 });
    await page.getByRole('button', { name: 'Submit for verification' }).click();

    await expect(page.getByRole('alert')).toContainText('changed since you opened it');
    await expect(statusBadge(page)).toHaveText('Cancelled');
    await expect(page.getByText('Order withdrawn')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Submit for verification' })).toBeDisabled();
  });

  test('a verified invoice is opened for financing', async ({ page }) => {
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'VERIFIED', version: 3 })]);
    await mockFinancing(page);
    await page.goto('/seller/invoices/b0000000-0000-4000-8000-000000000001');

    await expect(page.getByRole('button', { name: 'Submit for verification' })).toBeDisabled();
    await page.getByRole('button', { name: 'Make available to financiers' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'now open for financing' })).toBeVisible();
    await expect(statusBadge(page)).toHaveText('Open for financing');
    await expect(page.getByRole('region', { name: 'Financing offers' })).toContainText('No offers yet');
  });

  test('a failed load can be retried, and unknown invoices are reported', async ({ page }) => {
    await mockAuth(page, seller);
    const api = await mockInvoices(page, [storedInvoice({})]);
    api.failNext('GET', 503);
    await page.goto('/seller');
    await expect(page.getByRole('alert')).toContainText('temporarily unavailable');

    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('link', { name: 'INV-0001' })).toBeVisible();

    await page.goto('/seller/invoices/d0000000-0000-4000-8000-000000000009');
    await expect(page.getByRole('heading', { level: 1, name: 'Invoice not found' })).toBeVisible();
  });

  test('other roles cannot open the seller pages', async ({ page }) => {
    await mockAuth(page, { role: 'FINANCIER', name: 'Ravi Menon', email: 'ravi@example.test' });
    await page.goto('/seller/invoices/new');
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
  });
});
