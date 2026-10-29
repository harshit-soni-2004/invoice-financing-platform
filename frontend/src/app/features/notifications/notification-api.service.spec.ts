import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError } from '../../shared/api-failure';
import { NotificationApiService } from './notification-api.service';
import { notificationFixture } from './notification-testing';

const URL = 'http://gateway.test/api/notifications';

describe('NotificationApiService', () => {
  let api: NotificationApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(NotificationApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the caller’s notifications with paging only, never naming an owner', async () => {
    const result = api.list(2);
    const request = http.expectOne((req) => req.url === URL);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys().sort()).toEqual(['page', 'size']);
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({
      content: [notificationFixture(), notificationFixture({ id: 'n2', status: 'SKIPPED', skipReason: 'RECIPIENT_UNKNOWN', sentAt: null })],
      page: 2,
      size: 20,
      totalElements: 42,
      totalPages: 3,
    });

    const page = await result;
    expect(page.content.map((n) => n.status)).toEqual(['SENT', 'SKIPPED']);
    expect(page.content[1].skipReason).toBe('RECIPIENT_UNKNOWN');
  });

  it('rejects rows with an unknown delivery status', async () => {
    const result = api.list(0);
    http
      .expectOne((req) => req.url === URL)
      .flush({ content: [notificationFixture({ status: 'READ' as never })], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    await expectAsync(result).toBeRejectedWith(new ApiError({ kind: 'unexpected' }));
  });

  it('reads readAt, keeping null for unread rows', async () => {
    const result = api.list(0);
    http.expectOne((req) => req.url === URL).flush({
      content: [notificationFixture({ readAt: '2026-10-28T11:00:00Z' }), notificationFixture({ id: 'n2', readAt: null })],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });
    expect((await result).content.map((n) => n.readAt)).toEqual(['2026-10-28T11:00:00Z', null]);
  });

  it('gets the unread count, and treats a malformed answer as unexpected', async () => {
    const count = api.unreadCount();
    const request = http.expectOne(`${URL}/unread-count`);
    expect(request.request.method).toBe('GET');
    request.flush({ unreadCount: 7 });
    expect(await count).toBe(7);

    const malformed = api.unreadCount();
    http.expectOne(`${URL}/unread-count`).flush({ unreadCount: -1 });
    await expectAsync(malformed).toBeRejectedWith(new ApiError({ kind: 'unexpected' }));
  });

  it('marks one notification read with an empty POST and returns the stored row', async () => {
    const result = api.markRead('n0000000-0000-4000-8000-000000000001');
    const request = http.expectOne(`${URL}/n0000000-0000-4000-8000-000000000001/read`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(notificationFixture({ readAt: '2026-10-28T11:00:00Z' }));
    expect((await result).readAt).toBe('2026-10-28T11:00:00Z');
  });

  it('reports an unknown or another user’s notification as not found', async () => {
    const result = api.markRead('n-x');
    http.expectOne(`${URL}/n-x/read`).flush({ code: 'NOTIFICATION_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await expectAsync(result).toBeRejectedWith(new ApiError({ kind: 'not-found' }));
  });

  it('marks all read and returns how many changed', async () => {
    const result = api.markAllRead();
    const request = http.expectOne(`${URL}/read-all`);
    expect(request.request.method).toBe('POST');
    request.flush({ markedRead: 3 });
    expect(await result).toBe(3);
  });
});
