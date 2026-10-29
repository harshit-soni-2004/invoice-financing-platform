import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { SessionUser } from '../../core/auth/id-token';
import { ApiError, ApiFailure } from '../../shared/api-failure';
import { NotificationApiService } from './notification-api.service';
import { NOTIFICATION_SEEN_STORAGE, SEEN_KEY_PREFIX } from './notification-badge.service';
import { NotificationListComponent } from './notification-list.component';
import { MemoryStorage, notificationFixture } from './notification-testing';

describe('NotificationListComponent', () => {
  let fixture: ComponentFixture<NotificationListComponent>;
  let el: HTMLElement;
  let list: jasmine.Spy;
  let storage: MemoryStorage;

  const newer = notificationFixture({ id: 'n1', subject: 'Invoice INV-7 verified', createdAt: '2026-10-28T10:00:00Z' });
  const older = notificationFixture({
    id: 'n2',
    subject: 'Welcome to the Invoice Financing Platform',
    status: 'SKIPPED',
    skipReason: 'RECIPIENT_UNKNOWN',
    sentAt: null,
    createdAt: '2026-10-20T10:00:00Z',
  });
  const pageOf = (content: (typeof newer)[], totalPages = content.length ? 1 : 0) => ({
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages,
  });

  async function open(page?: string): Promise<void> {
    TestBed.configureTestingModule({
      imports: [NotificationListComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { user: signal<SessionUser | null>({ id: 'u-1', name: 'Asha', role: 'SELLER' }) } },
        { provide: NotificationApiService, useValue: { list } },
        { provide: NOTIFICATION_SEEN_STORAGE, useValue: storage },
      ],
    });
    fixture = TestBed.createComponent(NotificationListComponent);
    if (page) {
      fixture.componentRef.setInput('page', page);
    }
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  beforeEach(() => {
    list = jasmine.createSpy('list');
    storage = new MemoryStorage();
  });

  it('lists notifications with delivery details and marks the ones since the last visit as new', async () => {
    storage.setItem(SEEN_KEY_PREFIX + 'u-1', '2026-10-25T00:00:00Z');
    list.and.resolveTo(pageOf([newer, older]));
    await open();

    expect(list).toHaveBeenCalledOnceWith(0);
    const items = Array.from(el.querySelectorAll('.item'));
    expect(items[0].textContent).toContain('Invoice INV-7 verified');
    expect(items[0].textContent).toContain('Emailed');
    expect(items[0].querySelector('.badge')?.textContent?.trim()).toBe('New');
    expect(items[1].textContent).toContain('Not emailed: no email address was on file yet');
    expect(items[1].querySelector('.badge')).toBeNull();
    expect(storage.getItem(SEEN_KEY_PREFIX + 'u-1')).toBe('2026-10-28T10:00:00Z');
    expect(Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim())).toEqual(['Refresh']);
  });

  it('does not move the last-seen marker from a later page', async () => {
    list.and.resolveTo({ ...pageOf([older], 2), page: 1, totalElements: 21 });
    await open('2');
    expect(list).toHaveBeenCalledOnceWith(1);
    expect(storage.getItem(SEEN_KEY_PREFIX + 'u-1')).toBeNull();
  });

  it('shows an empty state', async () => {
    list.and.resolveTo(pageOf([]));
    await open();
    expect(el.textContent).toContain('No notifications yet');
  });

  const failures: [ApiFailure, string][] = [
    [{ kind: 'forbidden' }, 'You do not have permission'],
    [{ kind: 'conflict', code: null, currentStatus: null }, 'Refresh and try again'],
    [{ kind: 'stale' }, 'Refresh and try again'],
    [{ kind: 'network' }, 'could not be reached'],
  ];
  for (const [failure, text] of failures) {
    it(`shows a safe message for a ${failure.kind} failure and offers a retry`, async () => {
      list.and.rejectWith(new ApiError(failure));
      await open();
      expect(el.querySelector('[role="alert"]')?.textContent).toContain(text);
      expect(storage.length).toBe(0);

      list.and.resolveTo(pageOf([newer]));
      Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'))!.click();
      await settle();
      expect(el.querySelector('.item')).not.toBeNull();
    });
  }
});
