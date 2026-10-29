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
    ...overrides,
  };
}

/** A Storage stand-in, so tests never touch the real localStorage. */
export class MemoryStorage implements Storage {
  private readonly items = new Map<string, string>();

  get length(): number {
    return this.items.size;
  }

  clear(): void {
    this.items.clear();
  }

  getItem(key: string): string | null {
    return this.items.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.items.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.items.delete(key);
  }

  setItem(key: string, value: string): void {
    this.items.set(key, value);
  }
}
