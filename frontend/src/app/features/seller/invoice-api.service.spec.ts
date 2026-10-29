import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { InvoiceApiError, InvoiceApiService, InvoiceFailure } from './invoice-api.service';
import { invoiceFixture, summaryFixture } from './invoice-testing';
import { InvoiceDraft } from './invoice.models';

const URL = 'http://gateway.test/api/invoices';

describe('InvoiceApiService', () => {
  let api: InvoiceApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(InvoiceApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function failureOf(promise: Promise<unknown>): Promise<InvoiceFailure> {
    try {
      await promise;
    } catch (error) {
      expect(error).toBeInstanceOf(InvoiceApiError);
      return (error as InvoiceApiError).failure;
    }
    throw new Error('expected the request to fail');
  }

  it('lists through the gateway with paging and an optional status, and never names a seller', async () => {
    const result = api.list(2, 'DRAFT');
    const request = http.expectOne((req) => req.url === URL);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    expect(request.request.params.get('status')).toBe('DRAFT');
    expect(request.request.params.has('sellerId')).toBeFalse();
    request.flush({ content: [summaryFixture()], page: 2, size: 20, totalElements: 41, totalPages: 3 });

    const page = await result;
    expect(page.content[0].invoiceNumber).toBe('INV-2026/014');
    expect(page.totalPages).toBe(3);
  });

  it('omits the status filter when none is chosen', async () => {
    const result = api.list(0, null);
    const request = http.expectOne((req) => req.url === URL);
    expect(request.request.params.has('status')).toBeFalse();
    request.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    expect((await result).content).toEqual([]);
  });

  it('treats a malformed list response as unexpected', async () => {
    const result = api.list(0, null);
    http.expectOne((req) => req.url === URL).flush({ content: [{ id: 'x', status: 'SOMETHING' }], page: 0 });
    expect(await failureOf(result)).toEqual({ kind: 'unexpected' });
  });

  it('returns an invoice with the ETag from the response', async () => {
    const result = api.get('a0000000-0000-4000-8000-000000000001');
    http
      .expectOne(`${URL}/a0000000-0000-4000-8000-000000000001`)
      .flush(invoiceFixture({ version: 3 }), { headers: new HttpHeaders({ ETag: '"3"' }) });
    const { invoice, etag } = await result;
    expect(invoice.version).toBe(3);
    expect(etag).toBe('"3"');
  });

  it('falls back to the body version when the ETag is missing or weak', async () => {
    const result = api.get('id-1');
    http.expectOne(`${URL}/id-1`).flush(invoiceFixture({ version: 4 }), { headers: new HttpHeaders({ ETag: 'W/"9"' }) });
    expect((await result).etag).toBe('"4"');
  });

  it('creates a draft with exactly the fields the API accepts', async () => {
    const draft: InvoiceDraft = {
      invoiceNumber: 'INV-1',
      buyerName: 'Buyer',
      buyerGstin: null,
      description: null,
      amount: '125000.50',
      issueDate: '2026-10-20',
      dueDate: '2026-12-19',
    };
    const result = api.create(draft);
    const request = http.expectOne(URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(draft);
    request.flush(invoiceFixture(), { status: 201, statusText: 'Created', headers: new HttpHeaders({ ETag: '"0"' }) });
    expect((await result).invoice.status).toBe('DRAFT');
  });

  it('sends lifecycle actions with If-Match and no body', async () => {
    const result = api.transition('submit', 'id-1', '"2"');
    const request = http.expectOne(`${URL}/id-1/submit`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('"2"');
    expect(request.request.body).toBeNull();
    request.flush(invoiceFixture({ status: 'SUBMITTED', version: 3 }), { headers: new HttpHeaders({ ETag: '"3"' }) });
    expect(await result).toEqual(jasmine.objectContaining({ etag: '"3"' }));

    const listing = api.transition('list-for-financing', 'id-2', '"5"');
    http.expectOne(`${URL}/id-2/list-for-financing`).flush(invoiceFixture({ status: 'FINANCING_AVAILABLE', version: 6 }));
    expect((await listing).invoice.status).toBe('FINANCING_AVAILABLE');
  });

  it('sends review steps with If-Match, and a reason only when rejecting', async () => {
    const confirm = api.review('buyer-confirmation', 'id-1', '"1"');
    const confirmRequest = http.expectOne(`${URL}/id-1/buyer-confirmation`);
    expect(confirmRequest.request.method).toBe('POST');
    expect(confirmRequest.request.headers.get('If-Match')).toBe('"1"');
    expect(confirmRequest.request.body).toBeNull();
    confirmRequest.flush(invoiceFixture({ status: 'BUYER_CONFIRMED', version: 2 }), { headers: new HttpHeaders({ ETag: '"2"' }) });
    expect(await confirm).toEqual(jasmine.objectContaining({ etag: '"2"' }));

    const verify = api.review('verify', 'id-1', '"2"');
    const verifyRequest = http.expectOne(`${URL}/id-1/verify`);
    expect(verifyRequest.request.body).toBeNull();
    verifyRequest.flush(invoiceFixture({ status: 'VERIFIED', version: 3 }));
    expect((await verify).invoice.status).toBe('VERIFIED');

    const reject = api.review('reject', 'id-2', '"4"', 'Buyer denies the debt');
    const rejectRequest = http.expectOne(`${URL}/id-2/reject`);
    expect(rejectRequest.request.headers.get('If-Match')).toBe('"4"');
    expect(rejectRequest.request.body).toEqual({ reason: 'Buyer denies the debt' });
    rejectRequest.flush(invoiceFixture({ status: 'REJECTED', version: 5, statusReason: 'Buyer denies the debt' }));
    expect((await reject).invoice.statusReason).toBe('Buyer denies the debt');
  });

  describe('maps failures from the gateway and invoice-service', () => {
    const cases: [string, number, object | null, Record<string, string>, InvoiceFailure][] = [
      ['a network failure', 0, null, {}, { kind: 'network' }],
      [
        'field errors',
        400,
        {
          code: 'VALIDATION_FAILED',
          errors: [
            { field: 'amount', message: 'must be greater than or equal to 0.01' },
            { field: 'sellerId', message: 'ignored' },
          ],
        },
        {},
        { kind: 'invalid', fieldErrors: { amount: 'must be greater than or equal to 0.01' } },
      ],
      ['an expired session', 401, null, {}, { kind: 'unauthenticated' }],
      ['a missing role', 403, { code: 'ACCESS_DENIED' }, {}, { kind: 'forbidden' }],
      ['another seller’s or unknown invoice', 404, { code: 'INVOICE_NOT_FOUND' }, {}, { kind: 'not-found' }],
      ['a duplicate number', 409, { code: 'DUPLICATE_INVOICE_NUMBER' }, {}, { kind: 'duplicate-number' }],
      [
        'an invalid transition',
        409,
        { code: 'INVALID_STATUS_TRANSITION', currentStatus: 'SUBMITTED', requestedStatus: 'SUBMITTED' },
        {},
        { kind: 'invalid-transition', currentStatus: 'SUBMITTED' },
      ],
      ['a locked invoice', 409, { code: 'INVOICE_NOT_EDITABLE' }, {}, { kind: 'not-editable' }],
      ['a concurrent write', 409, { code: 'CONCURRENT_MODIFICATION' }, {}, { kind: 'concurrent' }],
      ['an unknown conflict', 409, { code: 'SOMETHING_ELSE' }, {}, { kind: 'unexpected' }],
      ['a stale version', 412, { code: 'VERSION_MISMATCH', currentETag: '"4"' }, {}, { kind: 'stale' }],
      [
        'a business rule',
        422,
        { code: 'DUE_DATE_TOO_SOON', detail: 'Due date must be at least 7 days away' },
        {},
        { kind: 'rule', field: 'dueDate', message: 'Due date must be at least 7 days away' },
      ],
      ['a rule without a known field', 422, { code: 'NEW_RULE' }, {}, { kind: 'rule', field: null, message: null }],
      ['rate limiting', 429, null, { 'Retry-After': '30' }, { kind: 'rate-limited', retryAfterSeconds: 30 }],
      ['an outage', 503, null, {}, { kind: 'unavailable' }],
      ['a server error', 500, { code: 'INTERNAL_ERROR' }, {}, { kind: 'unexpected' }],
    ];

    for (const [name, status, body, headers, expected] of cases) {
      it(name, async () => {
        const result = api.transition('submit', 'id-1', '"0"');
        const request = http.expectOne(`${URL}/id-1/submit`);
        if (status === 0) {
          request.error(new ProgressEvent('error'));
        } else {
          request.flush(body, { status, statusText: 'Error', headers: new HttpHeaders(headers) });
        }
        expect(await failureOf(result)).toEqual(expected);
      });
    }
  });
});
