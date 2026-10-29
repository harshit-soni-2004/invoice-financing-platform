import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../core/auth/auth.service';
import { SessionUser } from '../../core/auth/id-token';
import { ApiError } from '../../shared/api-failure';
import { NotificationApiService } from './notification-api.service';
import { NOTIFICATION_SEEN_STORAGE, NotificationBadgeService, SEEN_KEY_PREFIX } from './notification-badge.service';
import { MemoryStorage, notificationFixture } from './notification-testing';

describe('NotificationBadgeService', () => {
  const asha: SessionUser = { id: 'u-1', name: 'Asha Rao', role: 'SELLER' };
  let user: WritableSignal<SessionUser | null>;
  let storage: MemoryStorage;
  let list: jasmine.Spy;
  let badge: NotificationBadgeService;

  const page = (createdAts: string[], totalElements = createdAts.length) => ({
    content: createdAts.map((createdAt, i) => notificationFixture({ id: `n${i}`, createdAt })),
    page: 0,
    size: 20,
    totalElements,
    totalPages: 1,
  });

  beforeEach(() => {
    user = signal<SessionUser | null>(asha);
    storage = new MemoryStorage();
    list = jasmine.createSpy('list');
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { user } },
        { provide: NotificationApiService, useValue: { list } },
        { provide: NOTIFICATION_SEEN_STORAGE, useValue: storage },
      ],
    });
    badge = TestBed.inject(NotificationBadgeService);
  });

  it('counts every notification on the first page before the page was ever opened', async () => {
    list.and.resolveTo(page(['2026-10-28T10:00:00Z', '2026-10-28T09:00:00Z']));
    await badge.refresh();
    expect(list).toHaveBeenCalledOnceWith(0, 20);
    expect(badge.label()).toBe('2');
  });

  it('counts only notifications newer than the last-seen marker, comparing times rather than text', async () => {
    storage.setItem(SEEN_KEY_PREFIX + 'u-1', '2026-10-28T09:00:00Z');
    list.and.resolveTo(page(['2026-10-28T09:00:00.500Z', '2026-10-28T09:00:00Z', '2026-10-27T09:00:00Z']));
    await badge.refresh();
    expect(badge.label()).toBe('1');
  });

  it('shows "20+" when the whole first page is new and there are more', async () => {
    list.and.resolveTo(page(Array.from({ length: 20 }, (_, i) => `2026-10-28T09:${String(i).padStart(2, '0')}:00Z`), 35));
    await badge.refresh();
    expect(badge.label()).toBe('20+');
  });

  it('clears the count and stores only the newest timestamp when the user views the list', async () => {
    list.and.resolveTo(page(['2026-10-28T10:00:00Z']));
    await badge.refresh();
    badge.markSeen(page(['2026-10-28T10:00:00Z']).content);
    expect(badge.label()).toBeNull();
    expect(storage.getItem(SEEN_KEY_PREFIX + 'u-1')).toBe('2026-10-28T10:00:00Z');
    expect(storage.length).toBe(1);
  });

  it('keeps the previous count when a check fails, and shows nothing when signed out', async () => {
    list.and.resolveTo(page(['2026-10-28T10:00:00Z']));
    await badge.refresh();
    list.and.rejectWith(new ApiError({ kind: 'unavailable' }));
    await badge.refresh();
    expect(badge.label()).toBe('1');

    user.set(null);
    expect(badge.label()).toBeNull();
  });

  it('does not check again within 30 seconds, and shares one request between callers', async () => {
    list.and.resolveTo(page([]));
    await Promise.all([badge.refresh(), badge.refresh()]);
    await badge.refreshIfStale();
    expect(list).toHaveBeenCalledTimes(1);
  });

  it('ignores a count for a user who has since signed out', async () => {
    let resolve!: (value: ReturnType<typeof page>) => void;
    list.and.returnValue(new Promise((r) => (resolve = r)));
    const pending = badge.refresh();
    user.set({ id: 'u-2', name: 'Ravi', role: 'FINANCIER' });
    resolve(page(['2026-10-28T10:00:00Z']));
    await pending;
    expect(badge.label()).toBeNull();
  });
});
