import { Page, expect, test } from '@playwright/test';
import { mockNotifications, storedNotification } from './support/admin-mock';
import { mockAuth } from './support/auth-mock';
import { expectNoHorizontalOverflow, screenshot } from './support/ui';

const seller = { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' } as const;
const financier = { role: 'FINANCIER', name: 'Ravi Menon', email: 'ravi@example.test' } as const;

const seed = [
  storedNotification({ id: 'n1', subject: 'Invoice INV-0001 verified', createdAt: '2026-10-28T10:00:00Z' }),
  storedNotification({
    id: 'n2',
    eventType: 'UserRegistered',
    subject: 'Welcome to the Invoice Financing Platform',
    status: 'SKIPPED',
    skipReason: 'RECIPIENT_UNKNOWN',
    sentAt: null,
    createdAt: '2026-10-20T10:00:00Z',
  }),
];

/** At phone widths the header links sit in the collapsed menu. */
async function openMenu(page: Page, isMobile: boolean): Promise<void> {
  if (isMobile) {
    await page.getByRole('button', { name: /^Menu/ }).click();
  }
}

test.describe('notifications (mocked auth-service and gateway)', () => {
  test('the header shows how many notifications are new, and viewing them clears it', async ({ page, isMobile }, testInfo) => {
    await mockAuth(page, seller);
    const api = await mockNotifications(page, seed);
    await page.goto('/account');
    await expect(page.getByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();

    if (isMobile) {
      await expect(page.getByRole('button', { name: /^Menu\W+2 new notifications$/ })).toBeVisible();
    }
    await openMenu(page, isMobile);
    const link = page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Notifications 2 new' });
    await expect(link).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await link.click();

    await expect(page.getByRole('heading', { level: 1, name: 'Notifications' })).toBeVisible();
    const items = page.getByRole('list', { name: 'Notifications, newest first' }).getByRole('listitem');
    await expect(items).toHaveCount(2);
    await expect(items.nth(0)).toContainText('Invoice INV-0001 verified');
    await expect(items.nth(0)).toContainText('Emailed');
    await expect(items.nth(0)).toContainText('New');
    await expect(items.nth(1)).toContainText('Not emailed: no email address was on file yet');
    await expect(page.getByRole('button', { name: /mark/i })).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'notifications');

    await openMenu(page, isMobile);
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Notifications', exact: true })).toBeVisible();

    for (const request of api.requests) {
      expect([...new URL(request.url()).searchParams.keys()].sort()).toEqual(['page', 'size']);
    }
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain('mock-access');
    expect(stored).not.toContain('INV-0001');
  });

  test('every role has the notifications page, with an empty state', async ({ page }) => {
    await mockAuth(page, financier);
    await mockNotifications(page, []);
    await page.goto('/notifications');
    await expect(page.getByRole('heading', { name: 'No notifications yet' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });

  test('409, 412 and outages are explained with a retry; 403 shows the no-access page', async ({ page }) => {
    await mockAuth(page, seller);
    const api = await mockNotifications(page, seed);
    // The header's own check and the page each ask once, so both get the failure.
    api.failNext('GET', 409, { status: 409, code: 'CONFLICT', detail: 'internal detail' });
    api.failNext('GET', 409, { status: 409, code: 'CONFLICT', detail: 'internal detail' });
    await page.goto('/notifications');
    await expect(page.getByRole('alert')).toContainText('Your notifications could not be loaded.');
    await expect(page.getByText('internal detail')).toHaveCount(0);

    api.failNext('GET', 412);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('alert')).toContainText('Refresh and try again');

    api.failNext('GET', 503);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('alert')).toContainText('notification service is temporarily unavailable');

    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('listitem').filter({ hasText: 'Invoice INV-0001 verified' })).toBeVisible();

    api.failNext('GET', 403, { status: 403, code: 'ACCESS_DENIED' });
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
  });
});
