import { Page, expect, test } from '@playwright/test';
import { mockAuth } from './support/auth-mock';
import { disbursedAgreement, mockFinancing, storedOffer } from './support/seller-financing-mock';
import { mockInvoices, storedInvoice } from './support/invoice-mock';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

const seller = { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' } as const;
const INVOICE_URL = '/seller/invoices/b0000000-0000-4000-8000-000000000001';

const statusBadge = (page: Page) => page.locator('.page-header .badge');
const offersSection = (page: Page) => page.getByRole('region', { name: 'Financing offers' });
const agreementSection = (page: Page) => page.getByRole('region', { name: 'Financing agreement' });

test.describe('seller offers, agreement and repayments (mocked auth-service and gateway)', () => {
  test('a seller compares offers, backs out once, then accepts one with a single request', async ({ page }, testInfo) => {
    await setTheme(page, 'dark');
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCING_AVAILABLE', version: 4 })]);
    const api = await mockFinancing(page, {
      offers: [
        storedOffer({}),
        storedOffer({ id: 'f0000000-0000-4000-8000-000000000002', advanceAmount: 240000, annualRate: 0.115, createdAt: '2026-10-21T05:00:00Z' }),
        storedOffer({ id: 'f0000000-0000-4000-8000-000000000003', status: 'WITHDRAWN', statusReason: 'Limit reached', createdAt: '2026-10-19T05:00:00Z' }),
      ],
    });

    await page.goto(INVOICE_URL);
    await expect(statusBadge(page)).toHaveText('Open for financing');
    const offers = offersSection(page);
    await expect(offers.getByRole('row')).toHaveCount(4);
    await expect(offers.getByRole('row').nth(1)).toContainText('₹2,40,000.00');
    await expect(offers.getByRole('row').nth(1)).toContainText('11.5%');
    await expect(offers.getByRole('row').nth(3)).toContainText('Limit reached');
    await expect(offers.getByRole('button', { name: /Accept offer of/ })).toHaveCount(2);
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'seller-offers');

    await offers.getByRole('button', { name: 'Accept offer of ₹2,50,000.00' }).click();
    const dialog = page.getByRole('dialog', { name: 'Accept this offer?' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('closes every other open offer');
    await expect(dialog.getByRole('button', { name: 'Keep reviewing' })).toBeFocused();
    await expectNoHorizontalOverflow(page);
    await dialog.getByRole('button', { name: 'Keep reviewing' }).click();
    await expect(dialog).toBeHidden();
    expect(api.requests.filter((r) => r.method() === 'POST')).toHaveLength(0);

    await offers.getByRole('button', { name: 'Accept offer of ₹2,50,000.00' }).click();
    api.setDelay(1500);
    await dialog.getByRole('button', { name: 'Accept offer' }).dblclick();
    await expect(dialog.getByRole('button', { name: 'Accepting…' })).toBeDisabled();
    await expect(offers.getByRole('status')).toContainText('Offer accepted');
    api.setDelay(0);
    const accepts = api.requests.filter((r) => r.url().endsWith('/accept'));
    expect(accepts).toHaveLength(1);
    expect(accepts[0].url()).toContain('/offers/f0000000-0000-4000-8000-000000000001/accept');

    await expect(offers.getByRole('row').nth(1)).toContainText('Closed');
    await expect(offers.getByRole('row').nth(2)).toContainText('Accepted');
    await expect(offers.getByRole('button', { name: /Accept offer of/ })).toHaveCount(0);
    await expect(agreementSection(page)).toContainText('Awaiting approval');
    await expect(agreementSection(page)).toContainText('Not paid out yet.');
    // The invoice only becomes FINANCED when the financier pays out; nothing on the page pretends otherwise.
    await expect(statusBadge(page)).toHaveText('Open for financing');
    await expectNoHorizontalOverflow(page);
  });

  test('accepting an offer withdrawn meanwhile is refused and the real state is shown', async ({ page }) => {
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCING_AVAILABLE' })]);
    const api = await mockFinancing(page, { offers: [storedOffer({})] });
    await page.goto(INVOICE_URL);
    const offers = offersSection(page);
    await expect(offers.getByRole('row')).toHaveCount(2);

    Object.assign(api.offers[0], { status: 'WITHDRAWN', statusReason: 'Terms changed' });
    await offers.getByRole('button', { name: /Accept offer of/ }).click();
    await page.getByRole('dialog', { name: 'Accept this offer?' }).getByRole('button', { name: 'Accept offer' }).click();

    await expect(offers.getByRole('alert')).toContainText('This is now withdrawn, so that action is no longer possible.');
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(offers.getByRole('row').nth(1)).toContainText('Withdrawn');
    await expect(offers.getByRole('row').nth(1)).toContainText('Terms changed');
  });

  test('a seller rejects an offer with a reason; expired offers cannot be decided', async ({ page }) => {
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCING_AVAILABLE' })]);
    const api = await mockFinancing(page, {
      offers: [
        storedOffer({}),
        storedOffer({ id: 'f0000000-0000-4000-8000-000000000009', advanceAmount: 200000, expiresAt: '2026-10-01T00:00:00Z', createdAt: '2026-09-20T05:00:00Z' }),
      ],
    });
    await page.goto(INVOICE_URL);
    const offers = offersSection(page);
    await expect(offers.getByRole('button', { name: 'Accept offer of ₹2,00,000.00' })).toBeDisabled();
    await expect(offers.getByRole('row').nth(2)).toContainText('Validity has ended');

    await offers.getByRole('button', { name: 'Reject offer of ₹2,50,000.00' }).click();
    const dialog = page.getByRole('dialog', { name: 'Reject this offer?' });
    await dialog.getByLabel('Reason').fill('Rate too high');
    await dialog.getByRole('button', { name: 'Reject offer' }).click();
    await expect(offers.getByRole('status')).toContainText('Offer rejected.');
    await expect(offers.getByRole('row').nth(1)).toContainText('Rejected');
    const reject = api.requests.find((r) => r.url().endsWith('/reject'))!;
    expect(reject.postDataJSON()).toEqual({ reason: 'Rate too high' });
  });

  test('a financed invoice shows its agreement, schedule with penalty, and a repayment recorded exactly once', async ({ page }, testInfo) => {
    await setTheme(page, 'light');
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCED', financedAt: '2026-09-21T05:00:00Z' })]);
    const api = await mockFinancing(page, { agreements: [disbursedAgreement()] });

    await page.goto(INVOICE_URL);
    const agreement = agreementSection(page);
    await expect(agreement).toContainText('Disbursed');
    await expect(agreement).toContainText('₹1,27,710.50 is overdue');
    await expect(agreement).toContainText('Paid out (simulated)');
    await expect(agreement).toContainText('SIM-0011223344556677');
    const schedule = agreement.getByRole('table', { name: 'Repayment schedule' });
    await expect(schedule.getByRole('row').nth(1)).toContainText('Overdue');
    await expect(schedule.getByRole('row').nth(1)).toContainText('₹210.50 unpaid');
    await expect(agreement).toContainText('No repayments recorded yet.');
    await expect(page.getByRole('region', { name: 'Financing offers' })).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'seller-agreement');

    await agreement.getByRole('button', { name: 'Record repayment' }).click();
    const dialog = page.getByRole('dialog', { name: 'Record repayment' });
    await expect(dialog).toContainText('₹2,55,210.50');
    await expect(dialog.getByLabel('Amount (₹)')).toBeFocused();
    await dialog.getByLabel('Amount (₹)').fill('10.555');
    await dialog.getByRole('button', { name: 'Record repayment' }).click();
    await expect(dialog.getByText('at most 2 decimal places')).toBeVisible();
    await dialog.getByLabel('Amount (₹)').fill('300000');
    await dialog.getByRole('button', { name: 'Record repayment' }).click();
    await expect(dialog.getByText('more than what is outstanding')).toBeVisible();
    expect(api.requests.filter((r) => r.method() === 'POST')).toHaveLength(0);
    await expectNoHorizontalOverflow(page);

    await dialog.getByLabel('Amount (₹)').fill('50,000');
    api.setDelay(1500);
    await dialog.getByRole('button', { name: 'Record repayment' }).dblclick();
    await expect(dialog.getByRole('button', { name: 'Recording…' })).toBeDisabled();
    await expect(agreement.getByRole('status')).toContainText('Repayment of ₹50,000.00 recorded.');
    api.setDelay(0);
    const posts = api.requests.filter((r) => r.method() === 'POST' && r.url().endsWith('/repayments'));
    expect(posts).toHaveLength(1);
    expect(posts[0].postDataJSON()).toEqual({ amount: '50000.00' });
    expect(await posts[0].headerValue('idempotency-key')).toMatch(/^[A-Za-z0-9_-]{8,100}$/);

    await expect(agreement).toContainText('Repaying');
    const history = agreement.getByRole('table', { name: 'Repayments recorded, oldest first' });
    await expect(history.getByRole('row')).toHaveCount(2);
    await expect(history.getByRole('row').nth(1)).toContainText('₹210.50');
    await expect(agreement).toContainText('₹2,05,210.50');
  });

  test('a repayment whose response was lost is retried with the same key and not recorded twice', async ({ page }) => {
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCED' })]);
    const api = await mockFinancing(page, { agreements: [disbursedAgreement()] });
    await page.goto(INVOICE_URL);
    const agreement = agreementSection(page);
    await agreement.getByRole('button', { name: 'Record repayment' }).click();
    const dialog = page.getByRole('dialog', { name: 'Record repayment' });

    api.dropNextRepaymentResponse();
    await dialog.getByLabel('Amount (₹)').fill('1000');
    await dialog.getByRole('button', { name: 'Record repayment' }).click();
    await expect(dialog.getByRole('alert')).toContainText('it will not be recorded twice');
    await expect(dialog).toBeVisible();

    // The same amount written differently is the same repayment.
    await dialog.getByLabel('Amount (₹)').fill('1000.00');
    await dialog.getByRole('button', { name: 'Record repayment' }).click();
    await expect(agreement.getByRole('status')).toContainText('was already recorded');
    await expect(agreement.getByRole('status')).toContainText('It was not recorded a second time.');

    const posts = api.requests.filter((r) => r.method() === 'POST' && r.url().endsWith('/repayments'));
    expect(posts).toHaveLength(2);
    expect(await posts[1].headerValue('idempotency-key')).toBe(await posts[0].headerValue('idempotency-key'));
    expect(api.agreements[0].repayments).toHaveLength(1);
    await expect(agreement.getByRole('table', { name: 'Repayments recorded, oldest first' }).getByRole('row')).toHaveCount(2);
  });

  test('repayment conflicts and outages are explained without faking success', async ({ page }) => {
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCED' })]);
    const api = await mockFinancing(page, { agreements: [disbursedAgreement()] });

    api.failNext('GET', '/agreements', 503);
    await page.goto(INVOICE_URL);
    const agreement = agreementSection(page);
    await expect(agreement.getByRole('alert')).toContainText('temporarily unavailable');
    await agreement.getByRole('button', { name: 'Try again' }).click();
    await expect(agreement).toContainText('Disbursed');

    await agreement.getByRole('button', { name: 'Record repayment' }).click();
    const dialog = page.getByRole('dialog', { name: 'Record repayment' });
    await dialog.getByLabel('Amount (₹)').fill('500');
    api.failNext('POST', '/repayments', 429, { code: 'RATE_LIMITED' });
    await dialog.getByRole('button', { name: 'Record repayment' }).click();
    await expect(dialog.getByRole('alert')).toContainText('Too many requests');

    // Meanwhile the financier's records show the agreement settled.
    api.agreements[0].status = 'SETTLED';
    for (const i of api.agreements[0].schedule) {
      Object.assign(i, { remaining: 0, status: 'PAID' });
    }
    await dialog.getByRole('button', { name: 'Record repayment' }).click();
    await expect(dialog).toBeHidden();
    await expect(agreement.getByRole('alert')).toContainText('already fully repaid');
    await expect(agreement).toContainText('Settled');
    await expect(agreement.getByRole('button', { name: 'Record repayment' })).toBeDisabled();
    expect(api.agreements[0].repayments).toHaveLength(0);
  });

  test('a draft is edited with If-Match, a stale edit is refused, and an invoice is cancelled after confirmation', async ({ page }) => {
    await mockAuth(page, seller);
    const invoices = await mockInvoices(page, [storedInvoice({ version: 2 })]);
    await page.goto(INVOICE_URL);
    await expect(statusBadge(page)).toHaveText('Draft');

    await page.getByRole('button', { name: 'Edit details' }).click();
    await expect(page.getByLabel('Invoice number')).toBeFocused();
    await expect(page.getByRole('button', { name: 'Submit for verification' })).toBeDisabled();
    await page.getByLabel('Buyer name').fill('Kaveri Textiles Limited');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByRole('status')).toContainText('Changes saved.');
    await expect(page.getByText('Kaveri Textiles Limited')).toBeVisible();
    const put = invoices.requests.find((r) => r.method() === 'PUT')!;
    expect(await put.headerValue('if-match')).toBe('"2"');
    expect(put.postDataJSON()).toEqual(expect.objectContaining({ buyerName: 'Kaveri Textiles Limited', amount: '340000.00' }));
    await expectNoHorizontalOverflow(page);

    // Someone else changes the draft; the next edit must not overwrite it.
    Object.assign(invoices.invoices[0], { description: 'Edited in another tab', version: 9 });
    await page.getByRole('button', { name: 'Edit details' }).click();
    await page.getByLabel('Buyer name').fill('Overwrite attempt');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByRole('alert')).toContainText('changed since you opened it');
    await expect(page.getByText('Edited in another tab')).toBeVisible();
    await expect(page.getByLabel('Buyer name')).toHaveCount(0);
    expect(invoices.invoices[0].buyerName).toBe('Kaveri Textiles Limited');

    await page.getByRole('button', { name: 'Cancel invoice' }).click();
    const dialog = page.getByRole('dialog', { name: 'Cancel this invoice?' });
    await expect(dialog.getByRole('button', { name: 'Keep invoice' })).toBeFocused();
    await dialog.getByLabel('Reason').fill('Order withdrawn by buyer');
    invoices.setDelay(300);
    await dialog.getByRole('button', { name: 'Cancel invoice' }).dblclick();
    await expect(statusBadge(page)).toHaveText('Cancelled');
    invoices.setDelay(0);
    const cancels = invoices.requests.filter((r) => r.url().endsWith('/cancel'));
    expect(cancels).toHaveLength(1);
    expect(await cancels[0].headerValue('if-match')).toBe('"9"');
    expect(cancels[0].postDataJSON()).toEqual({ reason: 'Order withdrawn by buyer' });
    await expect(page.getByText('Order withdrawn by buyer')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel invoice' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Edit details' })).toBeDisabled();
  });

  test('cancel is not offered once an invoice is open for financing', async ({ page }) => {
    await mockAuth(page, seller);
    await mockInvoices(page, [storedInvoice({ status: 'FINANCING_AVAILABLE' })]);
    await mockFinancing(page);
    await page.goto(INVOICE_URL);
    await expect(offersSection(page)).toContainText('No offers yet');
    await expect(page.getByRole('button', { name: 'Cancel invoice' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Edit details' })).toBeDisabled();
  });
});
