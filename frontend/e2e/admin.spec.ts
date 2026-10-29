import { Page, expect, test } from '@playwright/test';
import { mockAdminUsers, mockAudit, storedAuditEvent, storedUser } from './support/admin-mock';
import { mockAuth } from './support/auth-mock';
import { mockInvoices, storedInvoice } from './support/invoice-mock';
import { expectNoHorizontalOverflow, screenshot, setTheme } from './support/ui';

const admin = { role: 'ADMIN', name: 'Priya Nair', email: 'priya@example.test' } as const;
const INVOICE_ID = 'b0000000-0000-4000-8000-000000000001';

const statusBadge = (page: Page) => page.locator('.page-header .badge');
const notice = (page: Page) => page.locator('.notice');

test.describe('admin console (mocked auth-service and gateway)', () => {
  test('the console links to each admin section', async ({ page }) => {
    await mockAuth(page, admin);
    await page.goto('/admin');
    await expect(page.getByRole('heading', { level: 1, name: 'Admin console' })).toBeVisible();
    const nav = page.getByRole('navigation', { name: 'Admin console' });
    await expect(nav.getByRole('link', { name: 'Invoice queue' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Financier approvals' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Audit log' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });

  test('other roles cannot open the admin pages', async ({ page }) => {
    await mockAuth(page, { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' });
    await page.goto('/admin/financiers');
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
  });

  test.describe('financier approvals', () => {
    test('an admin approves a pending financier once, after auth-service confirms', async ({ page }, testInfo) => {
      await setTheme(page, 'light');
      await mockAuth(page, admin);
      const api = await mockAdminUsers(page, [
        storedUser(),
        storedUser({ id: 'f0000000-0000-4000-8000-000000000002', displayName: 'Meera Iyer', email: 'meera@example.test' }),
      ]);
      await page.goto('/admin/financiers');

      await expect(page.getByRole('heading', { level: 1, name: 'Financier approvals' })).toBeVisible();
      await expect(page.getByText('2 accounts waiting for approval')).toBeVisible();
      await expect(page.getByText('Rejecting an application is final')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Reject Ravi Menon' })).toBeEnabled();
      const list = api.requests[0];
      expect(new URL(list.url()).searchParams.get('role')).toBe('FINANCIER');
      expect(new URL(list.url()).searchParams.get('status')).toBe('PENDING_APPROVAL');
      await expectNoHorizontalOverflow(page);
      await screenshot(page, testInfo, 'admin-financiers');

      api.setDelay(400);
      await page.getByRole('button', { name: 'Approve Ravi Menon' }).dblclick();
      await expect(page.getByRole('button', { name: 'Approve Ravi Menon' })).toBeDisabled();
      await expect(page.getByRole('status')).toContainText('Ravi Menon is approved and can now sign in.');
      api.setDelay(0);
      expect(api.requests.filter((r) => r.method() === 'POST')).toHaveLength(1);
      await expect(page.getByRole('row').filter({ hasText: 'Ravi Menon' })).toContainText('Approved');
      await expect(page.getByRole('button', { name: 'Approve Meera Iyer' })).toBeEnabled();
    });

    test('409, 403 and 412 are explained and never shown as approved', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockAdminUsers(page, [
        storedUser(),
        storedUser({ id: 'f0000000-0000-4000-8000-000000000002', displayName: 'Meera Iyer', email: 'meera@example.test' }),
      ]);
      await page.goto('/admin/financiers');
      await expect(page.getByRole('button', { name: 'Approve Ravi Menon' })).toBeVisible();

      // Approved meanwhile by another administrator.
      api.rows[0].status = 'ACTIVE';
      await page.getByRole('button', { name: 'Approve Ravi Menon' }).click();
      await expect(page.getByRole('alert')).toContainText('Ravi Menon is no longer waiting for approval. It is now approved.');
      await expect(page.getByRole('button', { name: 'Approve Ravi Menon' })).toHaveCount(0);

      api.failNext('POST', 403, { status: 403, code: 'ACCESS_DENIED', detail: 'internal detail' });
      await page.getByRole('button', { name: 'Approve Meera Iyer' }).click();
      await expect(page.getByRole('alert')).toContainText('You do not have permission to approve accounts.');
      await expect(page.getByRole('row').filter({ hasText: 'Meera Iyer' })).toContainText('Pending approval');

      api.failNext('POST', 412);
      await page.getByRole('button', { name: 'Approve Meera Iyer' }).click();
      await expect(page.getByRole('alert')).toContainText('Meera Iyer was not approved.');
      await expect(page.getByRole('row').filter({ hasText: 'Meera Iyer' })).toContainText('Pending approval');
      await expect(page.getByText('internal detail')).toHaveCount(0);
      await expectNoHorizontalOverflow(page);
    });

    test('an admin rejects a pending financier after confirming, with an optional reason', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockAdminUsers(page, [
        storedUser(),
        storedUser({ id: 'f0000000-0000-4000-8000-000000000002', displayName: 'Meera Iyer', email: 'meera@example.test' }),
      ]);
      await page.goto('/admin/financiers');

      // Backing out sends nothing.
      await page.getByRole('button', { name: 'Reject Ravi Menon' }).click();
      const dialog = page.getByRole('dialog', { name: 'Reject Ravi Menon?' });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Keep pending' })).toBeFocused();
      await expectNoHorizontalOverflow(page);
      await page.keyboard.press('Escape');
      await expect(dialog).toBeHidden();
      expect(api.requests.filter((r) => r.method() === 'POST')).toHaveLength(0);

      await page.getByRole('button', { name: 'Reject Ravi Menon' }).click();
      await dialog.getByLabel('Reason (optional)').fill('Company registration could not be verified');
      api.setDelay(400);
      await dialog.getByRole('button', { name: 'Reject application' }).click();
      await expect(dialog.getByRole('button', { name: 'Rejecting…' })).toBeDisabled();
      await expect(page.getByRole('status')).toContainText("Ravi Menon's application is rejected.");
      api.setDelay(0);
      await expect(dialog).toBeHidden();

      const posts = api.requests.filter((r) => r.method() === 'POST');
      expect(posts).toHaveLength(1);
      expect(new URL(posts[0].url()).pathname).toBe('/api/admin/users/f0000000-0000-4000-8000-000000000001/reject');
      expect(posts[0].postDataJSON()).toEqual({ reason: 'Company registration could not be verified' });
      await expect(page.getByRole('row').filter({ hasText: 'Ravi Menon' })).toContainText('Rejected');
      await expect(page.getByRole('button', { name: 'Reject Ravi Menon' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Approve Ravi Menon' })).toHaveCount(0);

      // A rejection refused because another administrator approved first shows the real status.
      api.rows[1].status = 'ACTIVE';
      await page.getByRole('button', { name: 'Reject Meera Iyer' }).click();
      await page.getByRole('dialog', { name: 'Reject Meera Iyer?' }).getByRole('button', { name: 'Reject application' }).click();
      await expect(page.getByRole('alert')).toContainText('Meera Iyer is no longer waiting for approval. It is now approved.');
      await expect(page.getByRole('row').filter({ hasText: 'Meera Iyer' })).toContainText('Approved');
      expect(api.requests.filter((r) => r.method() === 'POST').at(-1)?.postDataJSON()).toEqual({});
    });

    test('a rejection that fails in transit keeps the dialog open; 403 is explained', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockAdminUsers(page, [storedUser()]);
      await page.goto('/admin/financiers');

      api.failNext('POST', 503);
      await page.getByRole('button', { name: 'Reject Ravi Menon' }).click();
      const dialog = page.getByRole('dialog', { name: 'Reject Ravi Menon?' });
      await dialog.getByRole('button', { name: 'Reject application' }).click();
      await expect(dialog.getByRole('alert')).toContainText('Ravi Menon was not rejected.');
      await expect(dialog.getByRole('alert')).toContainText('temporarily unavailable');
      await expect(page.getByText('internal detail')).toHaveCount(0);
      await expect(page.getByRole('row').filter({ hasText: 'Ravi Menon' })).toContainText('Pending approval');

      api.failNext('POST', 403, { status: 403, code: 'ACCESS_DENIED' });
      await dialog.getByRole('button', { name: 'Reject application' }).click();
      await expect(dialog).toBeHidden();
      await expect(page.getByRole('alert')).toContainText('You do not have permission to reject accounts.');
      await expect(page.getByRole('row').filter({ hasText: 'Ravi Menon' })).toContainText('Pending approval');
    });

    test('an empty queue and a failed load with retry', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockAdminUsers(page, []);
      api.failNext('GET', 503);
      await page.goto('/admin/financiers');
      await expect(page.getByRole('alert')).toContainText('temporarily unavailable');
      await page.getByRole('button', { name: 'Try again' }).click();
      await expect(page.getByRole('heading', { name: 'No accounts are waiting' })).toBeVisible();
    });
  });

  test.describe('invoice queue', () => {
    test('an admin confirms, verifies and rejects invoices with If-Match', async ({ page }, testInfo) => {
      await setTheme(page, 'dark');
      await mockAuth(page, admin);
      const api = await mockInvoices(page, [
        storedInvoice({ status: 'SUBMITTED', version: 1 }),
        storedInvoice({
          id: 'b0000000-0000-4000-8000-000000000002',
          invoiceNumber: 'INV-0002',
          status: 'SUBMITTED',
          version: 1,
          createdAt: '2026-10-02T05:00:00Z',
        }),
      ]);
      await page.goto('/admin/invoices');

      await expect(page.getByRole('heading', { level: 2, name: 'Awaiting buyer confirmation' })).toBeVisible();
      expect(new URL(api.requests[0].url()).searchParams.get('status')).toBe('SUBMITTED');
      await expect(page.getByText('Showing 1–2 of 2 invoices')).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await screenshot(page, testInfo, 'admin-invoice-queue');

      await page.getByRole('link', { name: 'Review invoice INV-0001' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Review invoice INV-0001' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Verify invoice' })).toBeDisabled();
      await expectNoHorizontalOverflow(page);

      api.setDelay(400);
      await page.getByRole('button', { name: 'Record buyer confirmation' }).dblclick();
      await expect(page.getByRole('button', { name: 'Recording confirmation…' })).toBeDisabled();
      await expect(page.getByRole('status')).toContainText('Buyer confirmation recorded.');
      api.setDelay(0);
      const confirms = api.requests.filter((r) => r.url().endsWith('/buyer-confirmation'));
      expect(confirms).toHaveLength(1);
      expect(await confirms[0].headerValue('if-match')).toBe('"1"');
      await expect(statusBadge(page)).toHaveText('Buyer confirmed');

      await page.getByRole('button', { name: 'Verify invoice' }).click();
      await expect(page.getByRole('status')).toContainText('Invoice verified.');
      await expect(statusBadge(page)).toHaveText('Verified');
      const verify = api.requests.find((r) => r.url().endsWith('/verify'))!;
      expect(await verify.headerValue('if-match')).toBe('"2"');
      await expect(page.getByRole('button', { name: 'Reject invoice' })).toBeDisabled();
      await screenshot(page, testInfo, 'admin-invoice-review');

      await page.getByRole('link', { name: '← Back to the invoice queue' }).click();
      await page.getByRole('link', { name: 'Review invoice INV-0002' }).click();
      await page.getByRole('button', { name: 'Reject invoice' }).click();
      await expect(page.getByText('Enter the reason for rejecting this invoice.')).toBeVisible();
      await expect(page.getByLabel('Reason for rejection')).toBeFocused();
      expect(api.requests.filter((r) => r.url().endsWith('/reject'))).toHaveLength(0);

      await page.getByLabel('Reason for rejection').fill('Buyer denies the debt');
      await page.getByRole('button', { name: 'Reject invoice' }).click();
      await expect(page.getByRole('status')).toContainText('Invoice rejected.');
      await expect(statusBadge(page)).toHaveText('Rejected');
      const reject = api.requests.find((r) => r.url().endsWith('/reject'))!;
      expect(reject.postDataJSON()).toEqual({ reason: 'Buyer denies the debt' });
      expect(await reject.headerValue('if-match')).toBe('"1"');

      await page.getByRole('navigation', { name: 'Admin console' }).getByRole('link', { name: 'Invoice queue' }).click();
      await page.getByRole('link', { name: 'Awaiting verification' }).click();
      await expect(page.getByRole('heading', { name: 'Nothing to review' })).toBeVisible();
      for (const request of api.requests) {
        expect(request.url()).not.toContain('sellerId');
      }
    });

    test('412, 409 and 403 on review steps show safe messages and the stored state', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockInvoices(page, [storedInvoice({ status: 'SUBMITTED', version: 1 })]);
      await page.goto(`/admin/invoices/${INVOICE_ID}`);
      await expect(statusBadge(page)).toHaveText('Submitted');

      // Cancelled by the seller meanwhile, which bumps the version: invoice-service answers 412.
      Object.assign(api.invoices[0], { status: 'CANCELLED', statusReason: 'Order withdrawn', version: 2 });
      await page.getByRole('button', { name: 'Record buyer confirmation' }).click();
      await expect(page.getByRole('alert')).toContainText('changed since you opened it');
      await expect(statusBadge(page)).toHaveText('Cancelled');
      await expect(page.getByText('Order withdrawn')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Record buyer confirmation' })).toBeDisabled();
      await expect(page.getByRole('button', { name: 'Reject invoice' })).toBeDisabled();

      Object.assign(api.invoices[0], { status: 'BUYER_CONFIRMED', statusReason: null, version: 3 });
      await page.getByRole('button', { name: 'Refresh' }).click();
      await expect(statusBadge(page)).toHaveText('Buyer confirmed');

      api.failNext('POST', 409, { status: 409, code: 'INVALID_STATUS_TRANSITION', currentStatus: 'VERIFIED', detail: 'internal detail' });
      Object.assign(api.invoices[0], { status: 'VERIFIED', version: 4 });
      await page.getByRole('button', { name: 'Verify invoice' }).click();
      await expect(page.getByRole('alert')).toContainText('This invoice is now verified, so that action is no longer possible.');
      await expect(statusBadge(page)).toHaveText('Verified');

      Object.assign(api.invoices[0], { status: 'SUBMITTED', version: 5 });
      await page.getByRole('button', { name: 'Refresh' }).click();
      await expect(statusBadge(page)).toHaveText('Submitted');
      api.failNext('POST', 403, { status: 403, code: 'ACCESS_DENIED', detail: 'internal detail' });
      await page.getByRole('button', { name: 'Record buyer confirmation' }).click();
      await expect(page.getByRole('alert')).toContainText('You do not have permission to do this.');
      await expect(statusBadge(page)).toHaveText('Submitted');
      await expect(page.getByText('internal detail')).toHaveCount(0);
      await expectNoHorizontalOverflow(page);
    });

    test('an unknown invoice and a failed queue load with retry', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockInvoices(page, [storedInvoice({ status: 'SUBMITTED' })]);
      api.failNext('GET', 503);
      await page.goto('/admin/invoices');
      await expect(page.getByRole('alert')).toContainText('temporarily unavailable');
      await page.getByRole('button', { name: 'Try again' }).click();
      await expect(page.getByRole('link', { name: 'Review invoice INV-0001' })).toBeVisible();

      await page.goto('/admin/invoices/d0000000-0000-4000-8000-000000000009');
      await expect(page.getByRole('heading', { level: 1, name: 'Invoice not found' })).toBeVisible();
    });
  });

  test.describe('audit log', () => {
    test('an admin pages and filters the read-only audit trail', async ({ page }, testInfo) => {
      await mockAuth(page, admin);
      const events = Array.from({ length: 23 }, (_, i) =>
        storedAuditEvent({
          id: i + 1,
          eventId: `e0000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`,
          eventType: i % 2 === 0 ? 'InvoiceSubmitted' : 'InvoiceVerified',
          occurredAt: new Date(Date.UTC(2026, 9, 1, 9, i)).toISOString(),
        }),
      );
      const api = await mockAudit(page, events);
      await page.goto('/admin/audit');

      await expect(page.getByRole('heading', { level: 1, name: 'Audit log' })).toBeVisible();
      await expect(page.getByText('Showing 1–20 of 23 events')).toBeVisible();
      await expectNoHorizontalOverflow(page);
      await screenshot(page, testInfo, 'admin-audit');

      await page.getByText('Event details').first().click();
      await expect(page.locator('pre').first()).toContainText('"invoiceNumber": "INV-0001"');

      await page.getByRole('link', { name: 'Next' }).click();
      await expect(page.getByText('Showing 21–23 of 23 events')).toBeVisible();
      expect(new URL(api.requests.at(-1)!.url()).searchParams.get('page')).toBe('1');

      await page.getByLabel('Event type').fill('Invoice Verified');
      await page.getByRole('button', { name: 'Apply filters' }).click();
      await expect(page.getByLabel('Event type')).toBeFocused();
      await expect(page.getByLabel('Event type')).toHaveAttribute('aria-invalid', 'true');

      await page.getByLabel('Event type').fill('InvoiceVerified');
      await page.getByRole('button', { name: 'Apply filters' }).click();
      await expect(page.getByText('Showing 1–11 of 11 events')).toBeVisible();
      const filtered = new URL(api.requests.at(-1)!.url()).searchParams;
      expect(filtered.get('eventType')).toBe('InvoiceVerified');
      expect(filtered.get('page')).toBe('0');
      await expect(page).toHaveURL(/eventType=InvoiceVerified/);

      await page.getByLabel('Record ID').fill('nothing-matches');
      await page.getByRole('button', { name: 'Apply filters' }).click();
      await expect(page.getByRole('heading', { name: 'No events match these filters' })).toBeVisible();
      await page.getByRole('button', { name: 'Show all events' }).click();
      await expect(page.getByText('Showing 1–20 of 23 events')).toBeVisible();
      expect(api.requests.every((r) => r.method() === 'GET')).toBe(true);
      await expectNoHorizontalOverflow(page);
    });

    test('refused filters and outages are explained with a retry; 403 shows the no-access page', async ({ page }) => {
      await mockAuth(page, admin);
      const api = await mockAudit(page, [storedAuditEvent()]);
      api.failNext('GET', 400, { status: 400, code: 'INVALID_QUERY', detail: 'internal detail' });
      await page.goto('/admin/audit');
      await expect(page.getByRole('alert')).toContainText('Some filters were not accepted.');
      await expect(page.getByText('internal detail')).toHaveCount(0);

      api.failNext('GET', 503);
      await page.getByRole('button', { name: 'Try again' }).click();
      await expect(page.getByRole('alert')).toContainText('audit service is temporarily unavailable');
      await page.getByRole('button', { name: 'Try again' }).click();
      await expect(page.locator('.event')).toHaveCount(1);

      api.failNext('GET', 403, { status: 403, code: 'ACCESS_DENIED' });
      await page.getByRole('button', { name: 'Refresh' }).click();
      await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
    });
  });
});
