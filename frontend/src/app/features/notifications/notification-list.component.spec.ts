import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { SessionUser } from '../../core/auth/id-token';
import { ApiError, ApiFailure } from '../../shared/api-failure';
import { NotificationApiService, NotificationItem } from './notification-api.service';
import { NotificationBadgeService } from './notification-badge.service';
import { NotificationListComponent } from './notification-list.component';
import { notificationFixture } from './notification-testing';

describe('NotificationListComponent', () => {
  let fixture: ComponentFixture<NotificationListComponent>;
  let el: HTMLElement;
  let list: jasmine.Spy;
  let unreadCount: jasmine.Spy;
  let markRead: jasmine.Spy;
  let markAllRead: jasmine.Spy;

  const unread = notificationFixture({ id: 'n1', subject: 'Invoice INV-7 verified', createdAt: '2026-10-28T10:00:00Z' });
  const read = notificationFixture({
    id: 'n2',
    subject: 'Welcome to the Invoice Financing Platform',
    status: 'SKIPPED',
    skipReason: 'RECIPIENT_UNKNOWN',
    sentAt: null,
    createdAt: '2026-10-20T10:00:00Z',
    readAt: '2026-10-21T08:00:00Z',
  });
  const pageOf = (content: NotificationItem[], totalPages = content.length ? 1 : 0) => ({
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
        { provide: NotificationApiService, useValue: { list, unreadCount, markRead, markAllRead } },
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

  const items = () => Array.from(el.querySelectorAll<HTMLElement>('.item'));
  const button = (label: string) => Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label);
  const markButton = (subject: string) => el.querySelector<HTMLButtonElement>(`button[aria-label="Mark as read: ${subject}"]`);
  const notice = () => el.querySelector<HTMLElement>('.notice');

  beforeEach(() => {
    list = jasmine.createSpy('list');
    unreadCount = jasmine.createSpy('unreadCount').and.resolveTo(1);
    markRead = jasmine.createSpy('markRead');
    markAllRead = jasmine.createSpy('markAllRead');
  });

  it('lists notifications with delivery details and the server’s read state', async () => {
    list.and.resolveTo(pageOf([unread, read]));
    await open();

    expect(list).toHaveBeenCalledOnceWith(0);
    expect(unreadCount).toHaveBeenCalledTimes(1);
    expect(items()[0].textContent).toContain('Invoice INV-7 verified');
    expect(items()[0].textContent).toContain('Emailed');
    expect(items()[0].querySelector('.badge')?.textContent?.trim()).toBe('Unread');
    expect(markButton('Invoice INV-7 verified')?.disabled).toBeFalse();
    expect(items()[1].textContent).toContain('Not emailed: no email address was on file yet');
    expect(items()[1].textContent).toContain('Read ');
    expect(items()[1].querySelector('.badge')).toBeNull();
    expect(markButton('Welcome to the Invoice Financing Platform')).toBeNull();
    expect(el.querySelector('.summary')?.textContent).toContain('1 unread');
    expect(button('Mark all as read')?.disabled).toBeFalse();
  });

  it('marks one notification read only after the server confirms, then recounts', async () => {
    list.and.resolveTo(pageOf([unread, read]));
    await open();
    let resolve!: (item: NotificationItem) => void;
    markRead.and.returnValue(new Promise((r) => (resolve = r)));
    unreadCount.and.resolveTo(0);

    markButton('Invoice INV-7 verified')!.click();
    await settle();
    expect(markRead).toHaveBeenCalledOnceWith('n1');
    expect(markButton('Invoice INV-7 verified')?.textContent?.trim()).toBe('Marking…');
    expect(markButton('Invoice INV-7 verified')?.disabled).toBeTrue();
    expect(items()[0].querySelector('.badge')?.textContent?.trim()).toBe('Unread');

    resolve({ ...unread, readAt: '2026-10-28T12:00:00Z' });
    await settle();

    expect(items()[0].querySelector('.badge')).toBeNull();
    expect(markButton('Invoice INV-7 verified')).toBeNull();
    expect(unreadCount).toHaveBeenCalledTimes(2);
    expect(el.querySelector('.summary')?.textContent).not.toContain('unread');
    expect(document.activeElement).toBe(items()[0]);
    expect(TestBed.inject(NotificationBadgeService).label()).toBeNull();
  });

  it('explains a failed mark-as-read and keeps the notification unread', async () => {
    list.and.resolveTo(pageOf([unread]));
    await open();
    markRead.and.rejectWith(new ApiError({ kind: 'unavailable' }));

    markButton('Invoice INV-7 verified')!.click();
    await settle();

    expect(notice()?.getAttribute('role')).toBe('alert');
    expect(notice()?.textContent).toContain('The notification was not marked as read.');
    expect(notice()?.textContent).toContain('notification service is temporarily unavailable');
    expect(items()[0].querySelector('.badge')?.textContent?.trim()).toBe('Unread');
    expect(markButton('Invoice INV-7 verified')?.disabled).toBeFalse();
  });

  it('reloads the list when the notification no longer exists', async () => {
    list.and.resolveTo(pageOf([unread]));
    await open();
    markRead.and.rejectWith(new ApiError({ kind: 'not-found' }));
    list.and.resolveTo(pageOf([]));

    markButton('Invoice INV-7 verified')!.click();
    await settle();

    expect(list).toHaveBeenCalledTimes(2);
    expect(notice()?.textContent).toContain('no longer exists');
    expect(el.textContent).toContain('No notifications yet');
  });

  it('marks all read, reloads the stored state and recounts', async () => {
    list.and.resolveTo(pageOf([unread, read]));
    unreadCount.and.resolveTo(4);
    await open();
    markAllRead.and.resolveTo(4);
    list.and.resolveTo(pageOf([{ ...unread, readAt: '2026-10-28T12:00:00Z' }, read]));
    unreadCount.and.resolveTo(0);

    button('Mark all as read')!.click();
    await settle();

    expect(markAllRead).toHaveBeenCalledTimes(1);
    expect(list).toHaveBeenCalledTimes(2);
    expect(notice()?.getAttribute('role')).toBe('status');
    expect(notice()?.textContent).toContain('Marked 4 notifications as read.');
    expect(el.querySelectorAll('.item .badge').length).toBe(0);
    expect(button('Mark all as read')?.disabled).toBeTrue();
  });

  it('offers "mark all" for unread notifications on other pages', async () => {
    list.and.resolveTo({ ...pageOf([read], 2), page: 1, totalElements: 21 });
    unreadCount.and.resolveTo(2);
    await open('2');
    expect(list).toHaveBeenCalledOnceWith(1);
    expect(button('Mark all as read')?.disabled).toBeFalse();
  });

  it('disables "mark all" when nothing is unread', async () => {
    list.and.resolveTo(pageOf([read]));
    unreadCount.and.resolveTo(0);
    await open();
    expect(button('Mark all as read')?.disabled).toBeTrue();
  });

  it('shows an empty state', async () => {
    list.and.resolveTo(pageOf([]));
    unreadCount.and.resolveTo(0);
    await open();
    expect(el.textContent).toContain('No notifications yet');
  });

  it('still lists notifications when the unread count cannot be loaded', async () => {
    list.and.resolveTo(pageOf([unread]));
    unreadCount.and.rejectWith(new ApiError({ kind: 'unavailable' }));
    await open();
    expect(items().length).toBe(1);
    expect(el.querySelector('.summary')?.textContent).not.toContain('unread');
    expect(button('Mark all as read')?.disabled).toBeFalse();
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

      list.and.resolveTo(pageOf([unread]));
      button('Try again')!.click();
      await settle();
      expect(el.querySelector('.item')).not.toBeNull();
    });
  }
});
