import { Page, expect, test } from '@playwright/test';
import { mockAuth } from './support/auth-mock';
import { mockFinancing, storedAgreement, storedOffer, storedOpportunity } from './support/financing-mock';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

const financier = { role: 'FINANCIER', name: 'Ravi Menon', email: 'ravi@example.test' } as const;
const OPPORTUNITY = '/financier/opportunities/b0000000-0000-4000-8000-000000000001';
const AGREEMENT = '/financier/agreements/e0000000-0000-4000-8000-000000000001';

const headerBadge = (page: Page) => page.locator('.page-header .badge');

async function fillOffer(page: Page, advance = '4,80,000'): Promise<void> {
  await page.getByLabel('Advance (₹)').fill(advance);
  await page.getByLabel('Annual rate (%)').fill('12.5');
  await page.getByLabel('Processing fee (₹)').fill('1000');
  await page.getByLabel('Installments').fill('2');
}

test.describe('financier workflow (mocked auth-service and gateway)', () => {
  test('a financier sorts opportunities, makes one offer despite a double click, and withdraws it', async ({ page }, testInfo) => {
    await setTheme(page, 'dark');
    await mockAuth(page, financier);
    const api = await mockFinancing(page, {
      opportunities: [
        storedOpportunity(),
        storedOpportunity({ invoiceId: 'b0000000-0000-4000-8000-000000000002', invoiceNumber: 'INV-0002', amount: 900000, maxAdvanceAmount: 882000 }),
      ],
    });

    await page.goto('/financier');
    await expect(page.getByRole('heading', { level: 1, name: 'Financing opportunities' })).toBeVisible();
    await expect(page.getByText('Showing 1–2 of 2 invoices')).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await page.getByLabel('Sort by').selectOption({ label: 'Amount, largest first' });
    await expect(page).toHaveURL(/sort=amount(%2C|,)desc/);
    await expect(page.getByRole('row').nth(1)).toContainText('INV-0002');
    const sorted = api.requests.filter((r) => r.url().includes('/opportunities?')).at(-1)!;
    expect(new URL(sorted.url()).searchParams.get('sort')).toBe('amount,desc');
    await screenshot(page, testInfo, 'financier-opportunities');

    await page.getByRole('link', { name: 'INV-0001' }).click();
    await expect(page).toHaveURL(new RegExp(`${OPPORTUNITY}$`));
    await expect(page.getByText('You have not made an offer on this invoice.')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await fillOffer(page);
    await expect(page.getByText(/If disbursed today: fee about/)).toBeVisible();

    api.setDelay(400);
    await page.getByRole('button', { name: 'Send offer' }).dblclick();
    await expect(page.getByRole('button', { name: 'Sending offer…' })).toBeDisabled();
    await expect(page.getByRole('status')).toContainText('Offer sent.');
    api.setDelay(0);
    const creates = api.requests.filter((r) => r.method() === 'POST' && r.url().endsWith('/api/financing/offers'));
    expect(creates).toHaveLength(1);
    const body = creates[0].postDataJSON();
    expect(body).toEqual({
      invoiceId: 'b0000000-0000-4000-8000-000000000001',
      advanceAmount: '480000',
      annualRate: '0.1250',
      processingFee: '1000',
      installmentCount: 2,
      expiresAt: expect.stringMatching(/Z$/),
    });
    expect(body).not.toHaveProperty('financierId');

    const offer = page.locator('.offer').first();
    await expect(offer).toContainText('Open');
    await expect(offer).toContainText('12.5% p.a.');
    await expect(page.getByText('You already have an open offer on this invoice.')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'financier-offer');

    api.setDelay(400);
    await page.getByRole('button', { name: 'Withdraw offer' }).dblclick();
    await expect(page.getByRole('status')).toContainText('Offer withdrawn.');
    api.setDelay(0);
    expect(api.requests.filter((r) => r.url().endsWith('/withdraw'))).toHaveLength(1);
    await expect(offer).toContainText('Withdrawn');
    await expect(page.getByRole('button', { name: 'Send offer' })).toBeVisible();

    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain('mock-access');
  });

  test('the offer form explains problems, from the browser and from financing-service', async ({ page }) => {
    await mockAuth(page, financier);
    const api = await mockFinancing(page, { opportunities: [storedOpportunity()] });
    await page.goto(OPPORTUNITY);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('INV-0001');

    await page.getByRole('button', { name: 'Send offer' }).click();
    await expect(page.getByText('Enter the advance.')).toBeVisible();
    await expect(page.getByLabel('Advance (₹)')).toBeFocused();

    await fillOffer(page, '4,95,000');
    await page.getByRole('button', { name: 'Send offer' }).click();
    await expect(page.getByText('The advance can be at most ₹4,90,000.00 for this invoice.')).toBeVisible();
    expect(api.requests.filter((r) => r.method() === 'POST')).toHaveLength(0);

    // The server's own cap is authoritative: here it is lower than the opportunity said.
    api.opportunities[0].maxAdvanceAmount = 400000;
    await page.getByLabel('Advance (₹)').fill('4,50,000');
    await page.getByRole('button', { name: 'Send offer' }).click();
    await expect(page.getByRole('alert').first()).toContainText('advanceAmount may be at most 400000.00');
    await expect(page.getByLabel('Advance (₹)')).toBeFocused();
    await expect(page.getByLabel('Advance (₹)')).toHaveAttribute('aria-invalid', 'true');
    await expectNoHorizontalOverflow(page);
  });

  test('an offer made elsewhere meanwhile is reported and shown', async ({ page }) => {
    await mockAuth(page, financier);
    const api = await mockFinancing(page, { opportunities: [storedOpportunity()] });
    await page.goto(OPPORTUNITY);
    await expect(page.getByRole('button', { name: 'Send offer' })).toBeVisible();

    api.offers.push(storedOffer());
    await fillOffer(page);
    await page.getByRole('button', { name: 'Send offer' }).click();

    await expect(page.getByText('You already have an open offer on this invoice. Withdraw it first')).toBeVisible();
    await expect(page.locator('.offer')).toContainText('₹4,50,000.00');
    await expect(page.getByRole('button', { name: 'Send offer' })).toHaveCount(0);
  });

  test('a financier approves an agreement and disburses it once, then sees the schedule', async ({ page }, testInfo) => {
    await setTheme(page, 'light');
    await mockAuth(page, financier);
    const api = await mockFinancing(page, { agreements: [storedAgreement()] });

    await page.goto('/financier/agreements');
    await expect(page.getByRole('heading', { level: 1, name: 'Your agreements' })).toBeVisible();
    await expect(page.getByRole('row').nth(1)).toContainText('Awaiting your approval');
    await expectNoHorizontalOverflow(page);
    await page.getByRole('link', { name: 'E0000000' }).click();
    await expect(page).toHaveURL(new RegExp(`${AGREEMENT}$`));

    await expect(page.getByRole('button', { name: 'Disburse advance' })).toBeDisabled();
    await page.getByRole('button', { name: 'Approve agreement' }).click();
    await expect(page.getByRole('status')).toContainText('Agreement approved.');
    await expect(headerBadge(page)).toHaveText('Approved');
    await expect(page.getByRole('button', { name: 'Approve agreement' })).toBeDisabled();

    await page.getByRole('button', { name: 'Disburse advance' }).click();
    await expect(page.getByText('Disburse ₹4,80,000.00 to the seller now?')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Confirm disbursement' })).toBeFocused();

    api.setDelay(400);
    await page.getByRole('button', { name: 'Confirm disbursement' }).dblclick();
    await expect(page.getByRole('button', { name: 'Disbursing…' })).toBeDisabled();
    await expect(page.getByRole('status')).toContainText('disbursed (simulated)');
    api.setDelay(0);

    const disburses = api.requests.filter((r) => r.url().endsWith('/disburse'));
    expect(disburses).toHaveLength(1);
    expect(await disburses[0].headerValue('idempotency-key')).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(await disburses[0].headerValue('if-match')).toBeNull();

    await expect(headerBadge(page)).toHaveText('Disbursed');
    await expect(page.getByRole('table')).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(2);
    await expect(page.getByText('SIM-000001', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Disburse advance' })).toBeDisabled();
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'financier-agreement');
  });

  test('a disbursement whose answer was lost is retried with the same key and not paid twice', async ({ page }) => {
    await mockAuth(page, financier);
    const api = await mockFinancing(page, { agreements: [storedAgreement({ status: 'APPROVED', approvedAt: '2026-10-28T10:00:00Z' })] });
    await page.goto(AGREEMENT);
    await expect(headerBadge(page)).toHaveText('Approved');

    // financing-service disburses, but the gateway times out before the answer arrives.
    api.failNext({ method: 'POST', path: /\/disburse$/, status: 504, body: { code: 'DOWNSTREAM_TIMEOUT' }, afterProcessing: true });
    api.failNext({ method: 'GET', path: /\/agreements\/[^/]+$/, status: 503 });
    await page.getByRole('button', { name: 'Disburse advance' }).click();
    await page.getByRole('button', { name: 'Confirm disbursement' }).click();
    await expect(page.getByRole('alert')).toContainText('Retrying is safe');
    await expect(headerBadge(page)).toHaveText('Approved');

    await page.getByRole('button', { name: 'Disburse advance' }).click();
    await page.getByRole('button', { name: 'Confirm disbursement' }).click();
    await expect(page.getByRole('status')).toContainText('had already been disbursed');
    await expect(headerBadge(page)).toHaveText('Disbursed');

    const keys = await Promise.all(api.requests.filter((r) => r.url().endsWith('/disburse')).map((r) => r.headerValue('idempotency-key')));
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
    expect(api.agreements[0].disbursement).not.toBeNull();
  });

  test('an agreement cancelled meanwhile is shown instead of being disbursed', async ({ page }) => {
    await mockAuth(page, financier);
    const api = await mockFinancing(page, { agreements: [storedAgreement({ status: 'APPROVED' })] });
    await page.goto(AGREEMENT);
    await expect(headerBadge(page)).toHaveText('Approved');

    Object.assign(api.agreements[0], { status: 'CANCELLED', statusReason: 'Cancelled by the financier desk' });
    await page.getByRole('button', { name: 'Disburse advance' }).click();
    await page.getByRole('button', { name: 'Confirm disbursement' }).click();

    await expect(page.getByRole('alert')).toContainText('This is now cancelled');
    await expect(headerBadge(page)).toHaveText('Cancelled');
    await expect(page.getByText('Cancelled by the financier desk')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Disburse advance' })).toBeDisabled();
  });

  test('the repayment schedule marks paid and overdue installments', async ({ page }) => {
    await mockAuth(page, financier);
    await mockFinancing(page, {
      agreements: [
        storedAgreement({
          status: 'REPAYMENT_IN_PROGRESS',
          feeAmount: 10468.49,
          totalRepayable: 490468.49,
          principalRepaid: 240000,
          feeRepaid: 5234.24,
          approvedAt: '2026-09-01T10:00:00Z',
          disbursedAt: '2026-09-01T11:00:00Z',
          schedule: [
            { sequenceNo: 1, dueDate: '2026-09-20', principalDue: 240000, feeDue: 5234.24, totalDue: 245234.24, principalPaid: 240000, feePaid: 5234.24, remaining: 0, status: 'PAID' },
            { sequenceNo: 2, dueDate: '2026-10-20', principalDue: 240000, feeDue: 5234.25, totalDue: 245234.25, principalPaid: 0, feePaid: 0, remaining: 245234.25, status: 'OVERDUE' },
          ],
          disbursement: { id: 'd1', agreementId: 'e0000000-0000-4000-8000-000000000001', amount: 480000, currency: 'INR', status: 'SIMULATED', reference: 'SIM-000042', disbursedAt: '2026-09-01T11:00:00Z' },
        }),
      ],
    });
    await page.goto(AGREEMENT);

    await expect(headerBadge(page)).toHaveText('Repaying');
    await expect(page.locator('tbody tr').nth(0)).toContainText('Paid');
    await expect(page.locator('tbody tr').nth(1)).toContainText('Overdue');
    await expect(page.locator('.summary-figures .danger')).toContainText('₹2,45,234.25');
    await expect(page.getByText('SIM-000042')).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });

  test('refusals, rate limits and outages get clear messages', async ({ page }) => {
    await mockAuth(page, financier);
    const api = await mockFinancing(page, { opportunities: [storedOpportunity()], agreements: [storedAgreement()] });

    // A refused page load goes to the app-wide no-access page (core auth interceptor).
    api.failNext({ method: 'GET', path: /\/opportunities$/, status: 403, body: { code: 'ACCESS_DENIED' } });
    await page.goto('/financier');
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();

    // A refused action stays on the page and explains that new financiers need approval.
    await page.goto(AGREEMENT);
    api.failNext({ method: 'POST', path: /\/approve$/, status: 403, body: { code: 'ACCESS_DENIED' } });
    await page.getByRole('button', { name: 'Approve agreement' }).click();
    await expect(page.getByRole('alert')).toContainText('New financier accounts need an administrator’s approval first.');
    await expect(headerBadge(page)).toHaveText('Awaiting your approval');

    api.failNext({ method: 'GET', path: /\/opportunities$/, status: 503 });
    await page.goto('/financier');
    await expect(page.getByRole('alert')).toContainText('temporarily unavailable');
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('link', { name: 'INV-0001' })).toBeVisible();

    await page.goto(AGREEMENT);
    api.failNext({ method: 'POST', path: /\/approve$/, status: 429, headers: { 'Retry-After': '9' } });
    await page.getByRole('button', { name: 'Approve agreement' }).click();
    await expect(page.getByRole('alert')).toContainText('Wait 9 seconds');
    await expect(page.getByRole('button', { name: 'Approve agreement' })).toBeEnabled();

    await page.goto('/financier/agreements/e0000000-0000-4000-8000-000000000099');
    await expect(page.getByRole('heading', { level: 1, name: 'Agreement not found' })).toBeVisible();

    await page.goto('/financier/opportunities/b0000000-0000-4000-8000-000000000099');
    await expect(page.getByRole('heading', { level: 1, name: 'This invoice is not open for offers' })).toBeVisible();
  });

  test('other roles cannot open the financier pages', async ({ page }) => {
    await mockAuth(page, { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' });
    await page.goto('/financier/agreements');
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
  });
});
