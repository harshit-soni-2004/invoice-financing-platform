import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError } from '../../shared/api-failure';
import { auditFixture } from './admin-testing';
import { AuditApiService, AuditFilter } from './audit-api.service';

const URL = 'http://gateway.test/api/audit-events';

const NONE: AuditFilter = { eventType: null, aggregateType: null, aggregateId: null, actorId: null, from: null, to: null };

describe('AuditApiService', () => {
  let api: AuditApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(AuditApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends only the filters that are set, with paging', async () => {
    const result = api.search(
      { ...NONE, eventType: 'InvoiceVerified', actorId: '7d4c2f9e-0000-4000-8000-000000000001', from: '2026-10-01T00:00:00.000Z' },
      3,
    );
    const request = http.expectOne((req) => req.url === URL);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys().sort()).toEqual(['actorId', 'eventType', 'from', 'page', 'size']);
    expect(request.request.params.get('eventType')).toBe('InvoiceVerified');
    expect(request.request.params.get('from')).toBe('2026-10-01T00:00:00.000Z');
    expect(request.request.params.get('page')).toBe('3');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({ content: [auditFixture({ actorId: null, correlationId: null })], page: 3, size: 20, totalElements: 61, totalPages: 4 });

    const page = await result;
    expect(page.content[0].eventType).toBe('InvoiceSubmitted');
    expect(page.content[0].actorId).toBeNull();
    expect(page.content[0].payload).toEqual({ invoiceNumber: 'INV-2026/014', status: 'SUBMITTED' });
  });

  it('treats a malformed page as unexpected and maps a refused filter to invalid', async () => {
    const malformed = api.search(NONE, 0);
    http.expectOne((req) => req.url === URL).flush({ content: [{ id: 'x' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    await expectAsync(malformed).toBeRejectedWith(new ApiError({ kind: 'unexpected' }));

    const refused = api.search(NONE, 0);
    http.expectOne((req) => req.url === URL).flush({ code: 'INVALID_QUERY' }, { status: 400, statusText: 'Bad Request' });
    try {
      await refused;
      fail('expected a failure');
    } catch (error) {
      expect((error as ApiError).failure).toEqual({ kind: 'invalid' });
    }
  });
});
