import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG } from '../../core/auth/auth-testing';
import { APP_CONFIG } from '../../core/config/app-config';
import { AGREEMENT_SCAN_PAGES, FinancingApiError, FinancingFailure, SellerFinancingApiService } from './financing-api.service';
import {
  AGREEMENT_ID,
  INVOICE_ID,
  agreementFixture,
  disbursementFixture,
  installmentFixture,
  offerFixture,
  repaymentFixture,
} from './financing-testing';

const URL = 'http://gateway.test/api/financing';

function page<T>(content: T[], number = 0, totalPages = 1) {
  return { content, page: number, size: content.length, totalElements: content.length, totalPages };
}

describe('SellerFinancingApiService', () => {
  let api: SellerFinancingApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: APP_CONFIG, useValue: TEST_CONFIG }],
    });
    api = TestBed.inject(SellerFinancingApiService);
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

  it('lists the offers on one invoice, newest first, without naming a seller', async () => {
    const result = api.offersForInvoice(INVOICE_ID);
    const request = http.expectOne((req) => req.url === `${URL}/offers`);
    expect(request.request.params.get('invoiceId')).toBe(INVOICE_ID);
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    expect(request.request.params.has('sellerId')).toBeFalse();
    // Amounts arrive as JSON numbers or strings depending on serialization; both parse.
    request.flush({ ...page([{ ...offerFixture(), advanceAmount: '100000.00', sellerId: 's', financierId: 'f' }]), totalElements: 1 });
    const { offers, truncated } = await result;
    expect(offers[0].advanceAmount).toBe(100000);
    expect(offers[0]).not.toEqual(jasmine.objectContaining({ financierId: jasmine.anything() }));
    expect(truncated).toBeFalse();
  });

  it('reports when more offers exist than one page holds', async () => {
    const result = api.offersForInvoice(INVOICE_ID);
    http.expectOne((req) => req.url === `${URL}/offers`).flush({ ...page([offerFixture()]), totalElements: 101 });
    expect((await result).truncated).toBeTrue();
  });

  it('accepts an offer with no body and returns the new agreement', async () => {
    const result = api.acceptOffer('offer-1');
    const request = http.expectOne(`${URL}/offers/offer-1/accept`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('Idempotency-Key')).toBeFalse();
    request.flush(agreementFixture({ status: 'PENDING_APPROVAL', feeAmount: null, totalRepayable: null, overdue: null }), {
      status: 201,
      statusText: 'Created',
    });
    expect((await result).status).toBe('PENDING_APPROVAL');
  });

  it('rejects an offer with an optional reason', async () => {
    let result = api.rejectOffer('offer-1', 'Rate too high');
    let request = http.expectOne(`${URL}/offers/offer-1/reject`);
    expect(request.request.body).toEqual({ reason: 'Rate too high' });
    request.flush(offerFixture({ status: 'REJECTED' }));
    expect((await result).status).toBe('REJECTED');

    result = api.rejectOffer('offer-1', null);
    request = http.expectOne(`${URL}/offers/offer-1/reject`);
    expect(request.request.body).toBeNull();
    request.flush(offerFixture({ status: 'REJECTED' }));
    await result;
  });

  it('finds the agreement for an invoice by scanning the sellerâ€™s agreements page by page', async () => {
    const result = api.agreementForInvoice(INVOICE_ID);
    let request = http.expectOne((req) => req.url === `${URL}/agreements` && req.params.get('page') === '0');
    expect(request.request.params.get('size')).toBe('100');
    request.flush(page([agreementFixture({ id: 'other', invoiceId: 'other-invoice' })], 0, 2));
    await new Promise((resolve) => setTimeout(resolve));
    request = http.expectOne((req) => req.url === `${URL}/agreements` && req.params.get('page') === '1');
    request.flush(page([agreementFixture()], 1, 2));
    expect((await result)?.id).toBe(AGREEMENT_ID);
  });

  it('returns null when no agreement matches, without scanning past the last page', async () => {
    const result = api.agreementForInvoice(INVOICE_ID);
    http.expectOne((req) => req.url === `${URL}/agreements`).flush(page([], 0, 0));
    expect(await result).toBeNull();
  });

  it('stops scanning after the page limit', async () => {
    const result = api.agreementForInvoice(INVOICE_ID);
    for (let n = 0; n < AGREEMENT_SCAN_PAGES; n++) {
      await new Promise((resolve) => setTimeout(resolve));
      http
        .expectOne((req) => req.url === `${URL}/agreements` && req.params.get('page') === String(n))
        .flush(page([agreementFixture({ invoiceId: 'other' })], n, 50));
    }
    expect(await result).toBeNull();
  });

  it('loads only the agreement while it is not paid out', async () => {
    const result = api.agreementView(AGREEMENT_ID);
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}`).flush(agreementFixture({ status: 'APPROVED', disbursedAt: null }));
    const view = await result;
    expect(view.disbursement).toBeNull();
    expect(view.schedule).toEqual([]);
    expect(view.repayments).toEqual([]);
  });

  it('loads the payout, schedule with penalty fields and repayments once disbursed', async () => {
    const result = api.agreementView(AGREEMENT_ID);
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}`).flush(agreementFixture({ status: 'REPAYMENT_IN_PROGRESS' }));
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/disbursement`).flush(disbursementFixture());
    http
      .expectOne(`${URL}/agreements/${AGREEMENT_ID}/schedule`)
      .flush([installmentFixture({ status: 'OVERDUE', penaltyAccrued: 120.5, penaltyRemaining: 120.5, penaltyAccruedThrough: '2026-10-28' })]);
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/repayments`).flush([repaymentFixture({ penaltyComponent: 20 })]);
    const view = await result;
    expect(view.disbursement?.reference).toBe('SIM-0011223344556677');
    expect(view.schedule[0]).toEqual(jasmine.objectContaining({ status: 'OVERDUE', penaltyRemaining: 120.5, penaltyAccruedThrough: '2026-10-28' }));
    expect(view.repayments[0].penaltyComponent).toBe(20);
  });

  it('treats a schedule row without its penalty fields as unexpected', async () => {
    const result = api.agreementView(AGREEMENT_ID);
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}`).flush(agreementFixture());
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/disbursement`).flush(disbursementFixture());
    const withoutPenalty: Record<string, unknown> = { ...installmentFixture() };
    delete withoutPenalty['penaltyAccrued'];
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/schedule`).flush([withoutPenalty]);
    http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/repayments`).flush([]);
    expect(await failureOf(result)).toEqual({ kind: 'unexpected' });
  });

  it('records a repayment with the Idempotency-Key and reports a replay', async () => {
    let result = api.recordRepayment(AGREEMENT_ID, '10000.00', 'key-12345678');
    let request = http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/repayments`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('key-12345678');
    expect(request.request.body).toEqual({ amount: '10000.00' });
    request.flush(repaymentFixture(), { status: 201, statusText: 'Created', headers: new HttpHeaders({ 'Idempotent-Replayed': 'false' }) });
    expect((await result).replayed).toBeFalse();

    result = api.recordRepayment(AGREEMENT_ID, '10000.00', 'key-12345678');
    request = http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/repayments`);
    request.flush(repaymentFixture(), { status: 200, statusText: 'OK', headers: new HttpHeaders({ 'Idempotent-Replayed': 'true' }) });
    expect((await result).replayed).toBeTrue();
  });

  describe('maps failures', () => {
    const cases: [string, number, Record<string, unknown> | null, Record<string, string>, FinancingFailure][] = [
      ['a lost connection', 0, null, {}, { kind: 'network' }],
      ['a missing or bad Idempotency-Key', 400, { code: 'IDEMPOTENCY_KEY_REQUIRED' }, {}, { kind: 'invalid' }],
      ['an ended session', 401, null, {}, { kind: 'unauthenticated' }],
      ['a forbidden role', 403, { code: 'ACCESS_DENIED' }, {}, { kind: 'forbidden' }],
      ['an unknown agreement', 404, { code: 'AGREEMENT_NOT_FOUND' }, {}, { kind: 'not-found' }],
      [
        'a settled agreement',
        409,
        { code: 'AGREEMENT_SETTLED' },
        {},
        { kind: 'conflict', code: 'AGREEMENT_SETTLED', currentStatus: null },
      ],
      [
        'a key reused for another amount',
        409,
        { code: 'IDEMPOTENCY_KEY_REUSED' },
        {},
        { kind: 'conflict', code: 'IDEMPOTENCY_KEY_REUSED', currentStatus: null },
      ],
      ['a precondition failure', 412, null, {}, { kind: 'stale' }],
      [
        'an amount over the outstanding balance',
        422,
        { code: 'REPAYMENT_EXCEEDS_OUTSTANDING', detail: 'The repayment is larger than the amount outstanding' },
        {},
        { kind: 'rule', code: 'REPAYMENT_EXCEEDS_OUTSTANDING', message: 'The repayment is larger than the amount outstanding' },
      ],
      ['rate limiting', 429, null, { 'Retry-After': '12' }, { kind: 'rate-limited', retryAfterSeconds: 12 }],
      ['an outage', 503, null, {}, { kind: 'unavailable' }],
    ];

    for (const [name, status, body, headers, expected] of cases) {
      it(name, async () => {
        const result = api.recordRepayment(AGREEMENT_ID, '1.00', 'key-12345678');
        const request = http.expectOne(`${URL}/agreements/${AGREEMENT_ID}/repayments`);
        if (status === 0) {
          request.error(new ProgressEvent('error'));
        } else {
          request.flush(body, { status, statusText: 'Error', headers: new HttpHeaders(headers) });
        }
        expect(await failureOf(result)).toEqual(expected);
      });
    }

    it('maps an expired offer on accept', async () => {
      const result = api.acceptOffer('offer-1');
      http.expectOne(`${URL}/offers/offer-1/accept`).flush({ code: 'OFFER_EXPIRED' }, { status: 409, statusText: 'Conflict' });
      expect(await failureOf(result)).toEqual({ kind: 'conflict', code: 'OFFER_EXPIRED', currentStatus: null });
    });
  });
});
