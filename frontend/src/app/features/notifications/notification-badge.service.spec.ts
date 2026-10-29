import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../core/auth/auth.service';
import { SessionUser } from '../../core/auth/id-token';
import { ApiError } from '../../shared/api-failure';
import { NotificationApiService } from './notification-api.service';
import { NotificationBadgeService } from './notification-badge.service';

describe('NotificationBadgeService', () => {
  const asha: SessionUser = { id: 'u-1', name: 'Asha Rao', role: 'SELLER' };
  let user: WritableSignal<SessionUser | null>;
  let unreadCount: jasmine.Spy;
  let badge: NotificationBadgeService;

  beforeEach(() => {
    user = signal<SessionUser | null>(asha);
    unreadCount = jasmine.createSpy('unreadCount');
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { user } },
        { provide: NotificationApiService, useValue: { unreadCount } },
      ],
    });
    badge = TestBed.inject(NotificationBadgeService);
  });

  it('shows notification-service’s unread count', async () => {
    unreadCount.and.resolveTo(3);
    await badge.refresh();
    expect(unreadCount).toHaveBeenCalledTimes(1);
    expect(badge.count()).toBe(3);
    expect(badge.label()).toBe('3');
  });

  it('shows no badge when nothing is unread, and "99+" for large counts', async () => {
    unreadCount.and.resolveTo(0);
    await badge.refresh();
    expect(badge.count()).toBe(0);
    expect(badge.label()).toBeNull();

    unreadCount.and.resolveTo(250);
    await badge.recount();
    expect(badge.label()).toBe('99+');
  });

  it('is unknown before the first check, never a guessed number', () => {
    expect(badge.count()).toBeNull();
    expect(badge.label()).toBeNull();
  });

  it('keeps the previous count when a check fails, and shows nothing when signed out', async () => {
    unreadCount.and.resolveTo(1);
    await badge.refresh();
    unreadCount.and.rejectWith(new ApiError({ kind: 'unavailable' }));
    await badge.recount();
    expect(badge.label()).toBe('1');

    user.set(null);
    expect(badge.count()).toBeNull();
    expect(badge.label()).toBeNull();
  });

  it('does not check again within 30 seconds, and shares one request between callers', async () => {
    unreadCount.and.resolveTo(0);
    await Promise.all([badge.refresh(), badge.refresh()]);
    await badge.refreshIfStale();
    expect(unreadCount).toHaveBeenCalledTimes(1);
  });

  it('recounts after a read-state change and drops the answer of a check that started before it', async () => {
    let resolveOld!: (count: number) => void;
    unreadCount.and.returnValue(new Promise<number>((r) => (resolveOld = r)));
    const old = badge.refresh();

    unreadCount.and.resolveTo(1);
    await badge.recount();
    expect(unreadCount).toHaveBeenCalledTimes(2);
    expect(badge.count()).toBe(1);

    resolveOld(5);
    await old;
    expect(badge.count()).toBe(1);
  });

  it('ignores a count for a user who has since signed out', async () => {
    let resolve!: (count: number) => void;
    unreadCount.and.returnValue(new Promise<number>((r) => (resolve = r)));
    const pending = badge.refresh();
    user.set({ id: 'u-2', name: 'Ravi', role: 'FINANCIER' });
    resolve(4);
    await pending;
    expect(badge.count()).toBeNull();
  });
});
