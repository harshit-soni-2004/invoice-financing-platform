import { NotificationItem } from './notification-api.service';

export function notificationFixture(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: 'n0000000-0000-4000-8000-000000000001',
    eventType: 'InvoiceVerified',
    subject: 'Invoice INV-2026/014 verified',
    status: 'SENT',
    skipReason: null,
    createdAt: '2026-10-28T09:00:00Z',
    sentAt: '2026-10-28T09:00:05Z',
    readAt: null,
    ...overrides,
  };
}
