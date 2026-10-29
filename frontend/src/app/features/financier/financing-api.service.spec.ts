import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { FinancingApiError, FinancingApiService, FinancingFailure } from './financing-api.service';
import {
  AGREEMENT_ID,
  INVOICE_ID,
  agreementFixture,
  disbursementFixture,
  installmentFixture,
  offerFixture,
  opportunityFixture,
} from './financier-testing';
import { OfferDraft } from './financier.models';

const URL = 'http://gateway.test/api/financing';

describe('FinancingApiService', () => {
  let api: FinancingApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(FinancingApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function failureOf(promise: Promise<unknown>): Promise<FinancingFailure> {
    try {
      await promise;
    } catch (error) {
      expect(error).toBeInstanceOf(FinancingApiError);
      return (error as FinancingApiError).failure;
    }
    throw new Error('expected the request to fail');
  }

  it('lists opportunities with paging and a whitelisted sort', async () => {
    const result = api.opportunities(1, { field: 'amount', direction: 'desc' });
    const request = http.expectOne((req) => req.url === `${URL}/opportunities`);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('sort')).toBe('amount,desc');
    request.flush({ content: [opportunityFixture()], page: 1, size: 20, totalElements: 21, totalPages: 2 });

    const page = await result;
    expect(page.content[0].maxAdvanceAmount).toBe(490000);
    expect(page.totalPages).toBe(2);
  });

  it('accepts amounts serialized as strings', async () => {
    const result = api.opportunity(INVOICE_ID);
    http.expectOne(`${URL}/opportunities/${INVOICE_ID}`).flush({ ...opportunityFixture(), amount: '500000.00', maxAdvanceAmount: '490000.00' });
    expect((await result).amount).toBe(500000);
  });

  it('treats a malformed opportunity as unexpected', async () => {
    const result = api.opportunity(INVOICE_ID);
    http.expectOne(`${URL}/opportunities/${INVOICE_ID}`).flush({ invoiceId: INVOICE_ID });
    expect(await failureOf(result)).toEqual({ kind: 'unexpected' });
  });

  it('asks for the financier’s own offers on one invoice without naming the financier', async () => {
    const result = api.offersForInvoice(INVOICE_ID);
    const request = http.expectOne((req) => req.url === `${URL}/offers`);
    expect(request.request.params.get('invoiceId')).toBe(INVOICE_ID);
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    expect(request.request.params.has('financierId')).toBeFalse();
    request.flush({ content: [offerFixture()], page: 0, size: 100, totalElements: 1, totalPages: 1 });
    expect((await result)[0].status).toBe('OFFERED');
  });

  it('creates an offer with exactly the fields the API accepts', async () => {
    const draft: OfferDraft = {
      invoiceId: INVOICE_ID,
      advanceAmount: '480000.00',
      annualRate: '0.1250',
      processingFee: '0.00',
      installmentCount: 2,
      expiresAt: '2026-11-05T04:45:00.000Z',
    };
    const result = api.createOffer(draft);
    const request = http.expectOne(`${URL}/offers`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(draft);
    expect(request.request.headers.has('Idempotency-Key')).toBeFalse();
    request.flush(offerFixture(), { status: 201, statusText: 'Created' });
    expect((await result).id).toBe('f0000000-0000-4000-8000-000000000001');
  });

  it('withdraws an offer without a body', async () => {
    const result = api.withdrawOffer('offer-1');
    const request = http.expectOne(`${URL}/offers/offer-1/withdraw`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(offerFixture({ status: 'WITHDRAWN' }));
    expect((await result).status).toBe('WITHDRAWN');
  });

  it('lists agreements with an optional status filter', async () => {
    const filtered = api.agreements(0, 'APPROVED');
    const request = http.expectOne((req) => req.url === `${URL}/agreements`);
    expect(request.request.params.get('status')).toBe('APPROVED');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    request.flush({ content: [agreementFixture({ status: 'APPROVED', overdue: null, overdueAmount: null })], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect((await filtered).content[0].overdue).toBeNull();

    const all = api.agreements(0, null);
    const unfiltered = http.expectOne((req) => req.url === `${URL}/agreements`);
    expect(unfiltered.request.params.has('status')).toBeFalse();
    unfiltered.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    expect((await all).content).toEqual([]);
  });

  it('approves without a body or If-Match (financing-service has no ETags)', async () => {
    const result = api.approve(AGREEMENT_ID);
    const request = http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/approve`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('If-Match')).toBeFalse();
    request.flush(agreementFixture({ status: 'APPROVED' }));
    expect((await result).status).toBe('APPROVED');
  });

  it('disburses with the given Idempotency-Key and reports a replay', async () => {
    const first = api.disburse(AGREEMENT_ID, 'key-12345678');
    const request = http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/disburse`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('key-12345678');
    request.flush(disbursementFixture(), { status: 201, statusText: 'Created', headers: new HttpHeaders({ 'Idempotent-Replayed': 'false' }) });
    expect(await first).toEqual({ disbursement: disbursementFixture(), replayed: false });

    const replay = api.disburse(AGREEMENT_ID, 'key-12345678');
    http
      .expectOne(`${URL}/agreements/${AGREEMENT_ID}/disburse`)
      .flush(disbursementFixture(), { headers: new HttpHeaders({ 'Idempotent-Replayed': 'true' }) });
    expect((await replay).replayed).toBeTrue();
  });

  it('reads the repayment schedule', async () => {
    const result = api.schedule(AGREEMENT_ID);
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/schedule`).flush([installmentFixture(), installmentFixture({ sequenceNo: 2, status: 'OVERDUE' })]);
    expect((await result).map((i) => i.status)).toEqual(['PENDING', 'OVERDUE']);
  });

  it('reports no disbursement before the payout instead of failing', async () => {
    const result = api.disbursement(AGREEMENT_ID);
    http
      .expectOne(`${URL}/agreements/${AGREEMENT_ID}/disbursement`)
      .flush({ code: 'DISBURSEMENT_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    expect(await result).toBeNull();
  });

  describe('maps failures from the gateway and financing-service', () => {
    const cases: [string, number, object | null, Record<string, string>, FinancingFailure][] = [
      ['a network failure', 0, null, {}, { kind: 'network' }],
      [
        'field errors',
        400,
        {
          code: 'VALIDATION_FAILED',
          errors: [
            { field: 'annualRate', message: 'numeric value out of bounds (<1 digits>.<4 digits> expected)' },
            { field: 'financierId', message: 'ignored' },
          ],
        },
        {},
        { kind: 'invalid', fieldErrors: { annualRate: 'numeric value out of bounds (<1 digits>.<4 digits> expected)' } },
      ],
      ['an expired session', 401, null, {}, { kind: 'unauthenticated' }],
      ['a missing role', 403, { code: 'ACCESS_DENIED' }, {}, { kind: 'forbidden' }],
      ['an unknown or foreign resource', 404, { code: 'AGREEMENT_NOT_FOUND' }, {}, { kind: 'not-found' }],
      ['a duplicate open offer', 409, { code: 'DUPLICATE_OPEN_OFFER' }, {}, { kind: 'duplicate-offer' }],
      ['an already financed invoice', 409, { code: 'INVOICE_ALREADY_FINANCED' }, {}, { kind: 'already-financed' }],
      ['an expired offer', 409, { code: 'OFFER_EXPIRED' }, {}, { kind: 'offer-expired' }],
      [
        'an invalid transition',
        409,
        { code: 'INVALID_STATUS_TRANSITION', currentStatus: 'DISBURSED', requestedStatus: 'DISBURSED' },
        {},
        { kind: 'invalid-transition', currentStatus: 'DISBURSED' },
      ],
      ['a concurrent write', 409, { code: 'CONCURRENT_MODIFICATION' }, {}, { kind: 'concurrent' }],
      ['a constraint conflict', 409, { code: 'CONSTRAINT_VIOLATION' }, {}, { kind: 'concurrent' }],
      ['a reused idempotency key', 409, { code: 'IDEMPOTENCY_KEY_REUSED' }, {}, { kind: 'idempotency-reused' }],
      ['an unknown conflict', 409, { code: 'SOMETHING_ELSE' }, {}, { kind: 'conflict' }],
      ['a precondition failure', 412, null, {}, { kind: 'stale' }],
      [
        'a business rule on a field',
        422,
        { code: 'ADVANCE_TOO_HIGH', detail: 'advanceAmount may be at most 490000.00 for this invoice' },
        {},
        { kind: 'rule', code: 'ADVANCE_TOO_HIGH', field: 'advanceAmount', message: 'advanceAmount may be at most 490000.00 for this invoice' },
      ],
      [
        'a business rule about the invoice',
        422,
        { code: 'INVOICE_NOT_ELIGIBLE', detail: 'The invoice is too close to its due date to be financed' },
        {},
        { kind: 'rule', code: 'INVOICE_NOT_ELIGIBLE', field: null, message: 'The invoice is too close to its due date to be financed' },
      ],
      ['rate limiting', 429, null, { 'Retry-After': '12' }, { kind: 'rate-limited', retryAfterSeconds: 12 }],
      ['an outage', 503, null, {}, { kind: 'unavailable' }],
      ['a timeout at the gateway', 504, { code: 'DOWNSTREAM_TIMEOUT' }, {}, { kind: 'unavailable' }],
      ['a server error', 500, { code: 'INTERNAL_ERROR' }, {}, { kind: 'unexpected' }],
    ];

    for (const [name, status, body, headers, expected] of cases) {
      it(name, async () => {
        const result = api.disburse(AGREEMENT_ID, 'key-12345678');
        const request = http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/disburse`);
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
