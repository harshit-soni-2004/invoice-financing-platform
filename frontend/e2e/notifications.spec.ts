import { Page, expect, test } from '@playwright/test';
import { mockNotifications, storedNotification } from './support/admin-mock';
import { mockAuth } from './support/auth-mock';
import { expectNoHorizontalOverflow, screenshot } from './support/ui';

const seller = { role: 'SELLER', name: 'Asha Rao', email: 'asha@example.test' } as const;
const financier = { role: 'FINANCIER', name: 'Ravi Menon', email: 'ravi@example.test' } as const;

const seed = [
  storedNotification({ id: 'n1', subject: 'Invoice INV-0001 verified', createdAt: '2026-10-28T10:00:00Z' }),
  storedNotification({ id: 'n3', subject: 'New offer on INV-0001', eventType: 'OfferCreated', createdAt: '2026-10-27T10:00:00Z' }),
  storedNotification({
    id: 'n2',
    eventType: 'UserRegistered',
    subject: 'Welcome to the Invoice Financing Platform',
    status: 'SKIPPED',
    skipReason: 'RECIPIENT_UNKNOWN',
    sentAt: null,
    createdAt: '2026-10-20T10:00:00Z',
    readAt: '2026-10-21T08:00:00Z',
  }),
];

/** At phone widths the header links sit in the collapsed menu. */
async function openMenu(page: Page, isMobile: boolean): Promise<void> {
  if (isMobile) {
    await page.getByRole('button', { name: /^Menu/ }).click();
  }
}

test.describe('notifications (mocked auth-service and gateway)', () => {
  test('the header shows the server’s unread count, and marking notifications read lowers it', async ({ page, isMobile }, testInfo) => {
    await mockAuth(page, seller);
    const api = await mockNotifications(page, seed);
    await page.goto('/account');
    await expect(page.getByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();

    if (isMobile) {
      await expect(page.getByRole('button', { name: /^Menu\W+2 unread notifications$/ })).toBeVisible();
    }
    await openMenu(page, isMobile);
    const nav = page.getByRole('navigation', { name: 'Primary' });
    await expect(nav.getByRole('link', { name: 'Notifications 2 unread' })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await nav.getByRole('link', { name: 'Notifications 2 unread' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Notifications' })).toBeVisible();
    const items = page.getByRole('list', { name: 'Notifications, newest first' }).getByRole('listitem');
    await expect(items).toHaveCount(3);
    await expect(items.nth(0)).toContainText('Invoice INV-0001 verified');
    await expect(items.nth(0)).toContainText('Emailed');
    await expect(items.nth(0)).toContainText('Unread');
    await expect(items.nth(2)).toContainText('Not emailed: no email address was on file yet');
    await expect(items.nth(2)).toContainText('Read ');
    await expect(page.locator('.summary')).toContainText('2 unread');
    await expectNoHorizontalOverflow(page);
    await screenshot(page, testInfo, 'notifications');

    // Opening the page alone changes nothing on the server.
    expect(api.requests.filter((r) => r.method() === 'POST')).toHaveLength(0);

    await page.getByRole('button', { name: 'Mark as read: Invoice INV-0001 verified' }).click();
    await expect(items.nth(0)).not.toContainText('Unread');
    await expect(page.getByRole('button', { name: 'Mark as read: Invoice INV-0001 verified' })).toHaveCount(0);
    await expect(page.locator('.summary')).toContainText('1 unread');
    await openMenu(page, isMobile);
    await expect(nav.getByRole('link', { name: 'Notifications 1 unread' })).toBeVisible();
    if (isMobile) {
      await page.getByRole('button', { name: /^Menu/ }).click();
    }

    await page.getByRole('button', { name: 'Mark all as read' }).click();
    await expect(page.getByRole('status')).toContainText('Marked 1 notification as read.');
    await expect(items.filter({ hasText: 'Unread' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Mark all as read' })).toBeDisabled();
    await openMenu(page, isMobile);
    await expect(nav.getByRole('link', { name: 'Notifications', exact: true })).toBeVisible();

    const posts = api.requests.filter((r) => r.method() === 'POST').map((r) => new URL(r.url()).pathname);
    expect(posts).toEqual(['/api/notifications/n1/read', '/api/notifications/read-all']);
    for (const request of api.requests.filter((r) => new URL(r.url()).pathname === '/api/notifications')) {
      expect([...new URL(request.url()).searchParams.keys()].sort()).toEqual(['page', 'size']);
    }
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain('mock-access');
    expect(stored).not.toContain('INV-0001');
  });

  test('a failed mark-as-read is explained and the notification stays unread', async ({ page }) => {
    await mockAuth(page, seller);
    const api = await mockNotifications(page, seed);
    await page.goto('/notifications');
    await expect(page.locator('.summary')).toContainText('2 unread');

    api.failNext('POST', 503);
    await page.getByRole('button', { name: 'Mark as read: Invoice INV-0001 verified' }).click();
    await expect(page.getByRole('alert')).toContainText('The notification was not marked as read.');
    await expect(page.getByRole('alert')).toContainText('notification service is temporarily unavailable');
    await expect(page.getByText('internal detail')).toHaveCount(0);
    await expect(page.getByRole('listitem').filter({ hasText: 'Invoice INV-0001 verified' })).toContainText('Unread');
    await expect(page.locator('.summary')).toContainText('2 unread');
  });

  test('every role has the notifications page, with an empty state', async ({ page }) => {
    await mockAuth(page, financier);
    await mockNotifications(page, []);
    await page.goto('/notifications');
    await expect(page.getByRole('heading', { name: 'No notifications yet' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Mark all as read' })).toBeDisabled();
    await expectNoHorizontalOverflow(page);
  });

  test('409, 412 and outages are explained with a retry; 403 shows the no-access page', async ({ page }) => {
    await mockAuth(page, seller);
    const api = await mockNotifications(page, seed);
    const LIST = '/api/notifications';
    api.failNext('GET', 409, { status: 409, code: 'CONFLICT', detail: 'internal detail' }, LIST);
    await page.goto('/notifications');
    await expect(page.getByRole('alert')).toContainText('Your notifications could not be loaded.');
    await expect(page.getByText('internal detail')).toHaveCount(0);

    api.failNext('GET', 412, undefined, LIST);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('alert')).toContainText('Refresh and try again');

    api.failNext('GET', 503, undefined, LIST);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('alert')).toContainText('notification service is temporarily unavailable');

    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('listitem').filter({ hasText: 'Invoice INV-0001 verified' })).toBeVisible();

    api.failNext('GET', 403, { status: 403, code: 'ACCESS_DENIED' }, LIST);
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect(page.getByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeVisible();
  });
});
