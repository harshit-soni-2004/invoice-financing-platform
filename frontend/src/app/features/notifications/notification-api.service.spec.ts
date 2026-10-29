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
});
