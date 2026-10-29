import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config';
import {
  AGREEMENT_STATUSES,
  Agreement,
  AgreementStatus,
  Disbursement,
  DisbursementResult,
  INSTALLMENT_STATUSES,
  Installment,
  OFFER_STATUSES,
  Offer,
  OfferDraft,
  OfferField,
  Opportunity,
  OpportunitySort,
  Page,
} from './financier.models';

export const PAGE_SIZE = 20;

export type FinancingFailure =
  | { kind: 'invalid'; fieldErrors: Partial<Record<OfferField, string>> }
  /** A business rule refused the request; `message` is financing-service's own wording. */
  | { kind: 'rule'; code: string | null; field: OfferField | null; message: string | null }
  | { kind: 'duplicate-offer' }
  | { kind: 'already-financed' }
  | { kind: 'offer-expired' }
  | { kind: 'invalid-transition'; currentStatus: string | null }
  | { kind: 'concurrent' }
  | { kind: 'idempotency-reused' }
  | { kind: 'conflict' }
  | { kind: 'stale' }
  | { kind: 'unauthenticated' }
  | { kind: 'forbidden' }
  | { kind: 'not-found' }
  | { kind: 'rate-limited'; retryAfterSeconds: number | null }
  | { kind: 'network' }
  | { kind: 'unavailable' }
  | { kind: 'unexpected' };

export class FinancingApiError extends Error {
  constructor(readonly failure: FinancingFailure) {
    super(`Financing request failed: ${failure.kind}`);
  }
}

const OFFER_FIELDS: readonly OfferField[] = ['advanceAmount', 'annualRate', 'processingFee', 'installmentCount', 'expiresAt'];

/** financing-service's 422 codes for offers, and the form field each one is about. */
const RULE_FIELDS: Readonly<Record<string, OfferField>> = {
  ADVANCE_NOT_POSITIVE: 'advanceAmount',
  ADVANCE_TOO_HIGH: 'advanceAmount',
  AMOUNT_PRECISION: 'advanceAmount',
  RATE_OUT_OF_RANGE: 'annualRate',
  RATE_PRECISION: 'annualRate',
  PROCESSING_FEE_INVALID: 'processingFee',
  INSTALLMENTS_OUT_OF_RANGE: 'installmentCount',
  MATURITY_TOO_SOON: 'installmentCount',
  EXPIRY_INVALID: 'expiresAt',
};

/**
 * The financier's side of financing-service, through the gateway. Requests never name a financier: financing-service
 * scopes offers and agreements to the user in the access token, which the auth interceptor attaches. financing-service
 * sends no ETags; concurrent changes come back as 409 instead.
 */
@Injectable({ providedIn: 'root' })
export class FinancingApiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/financing`;

  /** `page` is zero-based, as in the API. */
  async opportunities(page: number, sort: OpportunitySort): Promise<Page<Opportunity>> {
    const params = new HttpParams().set('page', page).set('size', PAGE_SIZE).set('sort', `${sort.field},${sort.direction}`);
    return parsed(parsePage(await call(this.http.get<unknown>(`${this.url}/opportunities`, { params })), parseOpportunity));
  }

  async opportunity(invoiceId: string): Promise<Opportunity> {
    return parsed(parseOpportunity(await call(this.http.get<unknown>(`${this.url}/opportunities/${encodeURIComponent(invoiceId)}`))));
  }

  /** The caller's own offers on one invoice, newest first (financing-service only returns a financier's own offers). */
  async offersForInvoice(invoiceId: string): Promise<Offer[]> {
    const params = new HttpParams().set('invoiceId', invoiceId).set('page', 0).set('size', 100).set('sort', 'createdAt,desc');
    return parsed(parsePage(await call(this.http.get<unknown>(`${this.url}/offers`, { params })), parseOffer)).content;
  }

  async createOffer(draft: OfferDraft): Promise<Offer> {
    return parsed(parseOffer(await call(this.http.post<unknown>(`${this.url}/offers`, draft))));
  }

  async withdrawOffer(offerId: string): Promise<Offer> {
    return parsed(parseOffer(await call(this.http.post<unknown>(`${this.url}/offers/${encodeURIComponent(offerId)}/withdraw`, null))));
  }

  async agreements(page: number, status: AgreementStatus | null): Promise<Page<Agreement>> {
    let params = new HttpParams().set('page', page).set('size', PAGE_SIZE).set('sort', 'createdAt,desc');
    if (status) {
      params = params.set('status', status);
    }
    return parsed(parsePage(await call(this.http.get<unknown>(`${this.url}/agreements`, { params })), parseAgreement));
  }

  async agreement(id: string): Promise<Agreement> {
    return parsed(parseAgreement(await call(this.http.get<unknown>(`${this.url}/agreements/${encodeURIComponent(id)}`))));
  }

  async approve(id: string): Promise<Agreement> {
    return parsed(parseAgreement(await call(this.http.post<unknown>(`${this.url}/agreements/${encodeURIComponent(id)}/approve`, null))));
  }

  /**
   * Simulated payout. The same key must be sent again when the same disbursement is retried, so financing-service can
   * answer a retry of a request that already succeeded with the original disbursement instead of an error.
   */
  async disburse(id: string, idempotencyKey: string): Promise<DisbursementResult> {
    const response = await call(
      this.http.post<unknown>(`${this.url}/agreements/${encodeURIComponent(id)}/disburse`, null, {
        observe: 'response',
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
      }),
    );
    const disbursement = parsed(parseDisbursement(response.body));
    return { disbursement, replayed: response.headers.get('Idempotent-Replayed')?.trim() === 'true' };
  }

  /** Empty until the agreement is disbursed: the fee, and so the schedule, is fixed at disbursement. */
  async schedule(id: string): Promise<Installment[]> {
    const body = await call(this.http.get<unknown>(`${this.url}/agreements/${encodeURIComponent(id)}/schedule`));
    if (!Array.isArray(body)) {
      throw new FinancingApiError({ kind: 'unexpected' });
    }
    return body.map((row) => parsed(parseInstallment(row)));
  }

  /** Null while the agreement has not been disbursed. */
  async disbursement(id: string): Promise<Disbursement | null> {
    try {
      return parsed(parseDisbursement(await call(this.http.get<unknown>(`${this.url}/agreements/${encodeURIComponent(id)}/disbursement`))));
    } catch (error) {
      if (error instanceof FinancingApiError && error.failure.kind === 'not-found') {
        return null;
      }
      throw error;
    }
  }
}

async function call<T>(request: Observable<T>): Promise<T> {
  try {
    return await firstValueFrom(request);
  } catch (error) {
    throw new FinancingApiError(error instanceof HttpErrorResponse ? failureFor(error) : { kind: 'unexpected' });
  }
}

function parsed<T>(value: T | null): T {
  if (value === null) {
    throw new FinancingApiError({ kind: 'unexpected' });
  }
  return value;
}

export function failureFor(error: HttpErrorResponse): FinancingFailure {
  const body: Record<string, unknown> = isObject(error.error) ? error.error : {};
  const code = typeof body['code'] === 'string' ? body['code'] : null;
  switch (error.status) {
    case 0:
      return { kind: 'network' };
    case 400:
      return { kind: 'invalid', fieldErrors: fieldErrors(body['errors']) };
    case 401:
      return { kind: 'unauthenticated' };
    case 403:
      return { kind: 'forbidden' };
    case 404:
      return { kind: 'not-found' };
    case 409:
      switch (code) {
        case 'DUPLICATE_OPEN_OFFER':
          return { kind: 'duplicate-offer' };
        case 'INVOICE_ALREADY_FINANCED':
          return { kind: 'already-financed' };
        case 'OFFER_EXPIRED':
          return { kind: 'offer-expired' };
        case 'INVALID_STATUS_TRANSITION':
          return { kind: 'invalid-transition', currentStatus: typeof body['currentStatus'] === 'string' ? body['currentStatus'] : null };
        case 'CONCURRENT_MODIFICATION':
        case 'CONSTRAINT_VIOLATION':
          return { kind: 'concurrent' };
        case 'IDEMPOTENCY_KEY_REUSED':
          return { kind: 'idempotency-reused' };
        default:
          return { kind: 'conflict' };
      }
    case 412:
    case 428:
      return { kind: 'stale' };
    case 422: {
      const detail = body['detail'];
      return {
        kind: 'rule',
        code,
        field: code ? (RULE_FIELDS[code] ?? null) : null,
        message: typeof detail === 'string' && detail.length > 0 && detail.length <= 300 ? detail : null,
      };
    }
    case 429:
      return { kind: 'rate-limited', retryAfterSeconds: retryAfter(error.headers.get('Retry-After')) };
    case 502:
    case 503:
    case 504:
      return { kind: 'unavailable' };
    default:
      return { kind: 'unexpected' };
  }
}

/** Reads `errors: [{ field, message }]` from the problem details, keeping only fields the offer form has. */
function fieldErrors(errors: unknown): Partial<Record<OfferField, string>> {
  const result: Partial<Record<OfferField, string>> = {};
  if (!Array.isArray(errors)) {
    return result;
  }
  for (const entry of errors) {
    if (!isObject(entry)) {
      continue;
    }
    const field = OFFER_FIELDS.find((name) => name === entry['field']);
    const message = entry['message'];
    if (field && typeof message === 'string' && result[field] === undefined) {
      result[field] = message;
    }
  }
  return result;
}

function retryAfter(header: string | null): number | null {
  return header !== null && /^\d+$/.test(header.trim()) ? Number(header.trim()) : null;
}

function parsePage<T>(body: unknown, parseRow: (row: unknown) => T | null): Page<T> | null {
  if (!isObject(body) || !Array.isArray(body['content'])) {
    return null;
  }
  const { page, size, totalElements, totalPages } = body;
  if (!isCount(page) || !isCount(size) || !isCount(totalElements) || !isCount(totalPages)) {
    return null;
  }
  const content: T[] = [];
  for (const row of body['content']) {
    const item = parseRow(row);
    if (item === null) {
      return null;
    }
    content.push(item);
  }
  return { content, page, size, totalElements, totalPages };
}

function parseOpportunity(row: unknown): Opportunity | null {
  if (!isObject(row)) {
    return null;
  }
  const amount = toAmount(row['amount']);
  const maxAdvanceAmount = toAmount(row['maxAdvanceAmount']);
  const { invoiceId, invoiceNumber, buyerName, currency, issueDate, dueDate, daysToMaturity } = row;
  if (
    !isText(invoiceId) ||
    !isText(invoiceNumber) ||
    !isText(buyerName) ||
    !isText(currency) ||
    !isText(issueDate) ||
    !isText(dueDate) ||
    typeof daysToMaturity !== 'number' ||
    !Number.isInteger(daysToMaturity) ||
    amount === null ||
    maxAdvanceAmount === null
  ) {
    return null;
  }
  return { invoiceId, invoiceNumber, buyerName, amount, currency, issueDate, dueDate, daysToMaturity, maxAdvanceAmount };
}

function parseOffer(row: unknown): Offer | null {
  if (!isObject(row)) {
    return null;
  }
  const advanceAmount = toAmount(row['advanceAmount']);
  const annualRate = toAmount(row['annualRate']);
  const processingFee = toAmount(row['processingFee']);
  const status = OFFER_STATUSES.find((s) => s === row['status']);
  const { id, invoiceId, installmentCount, currency, expiresAt, createdAt } = row;
  if (
    !isText(id) ||
    !isText(invoiceId) ||
    !isText(currency) ||
    !isText(expiresAt) ||
    !isText(createdAt) ||
    !isCount(installmentCount) ||
    advanceAmount === null ||
    annualRate === null ||
    processingFee === null ||
    !status
  ) {
    return null;
  }
  return {
    id,
    invoiceId,
    advanceAmount,
    annualRate,
    processingFee,
    installmentCount,
    currency,
    expiresAt,
    status,
    statusReason: optionalText(row['statusReason']),
    decidedAt: optionalText(row['decidedAt']),
    createdAt,
  };
}

function parseAgreement(row: unknown): Agreement | null {
  if (!isObject(row)) {
    return null;
  }
  const required = {
    invoiceAmount: toAmount(row['invoiceAmount']),
    advanceAmount: toAmount(row['advanceAmount']),
    annualRate: toAmount(row['annualRate']),
    processingFee: toAmount(row['processingFee']),
    principalRepaid: toAmount(row['principalRepaid']),
    feeRepaid: toAmount(row['feeRepaid']),
    outstanding: toAmount(row['outstanding']),
  };
  const status = AGREEMENT_STATUSES.find((s) => s === row['status']);
  const { id, offerId, invoiceId, installmentCount, currency, maturityDate, createdAt } = row;
  if (
    !isText(id) ||
    !isText(offerId) ||
    !isText(invoiceId) ||
    !isText(currency) ||
    !isText(maturityDate) ||
    !isText(createdAt) ||
    !isCount(installmentCount) ||
    !status ||
    Object.values(required).some((value) => value === null)
  ) {
    return null;
  }
  return {
    id,
    offerId,
    invoiceId,
    invoiceAmount: required.invoiceAmount!,
    advanceAmount: required.advanceAmount!,
    annualRate: required.annualRate!,
    processingFee: required.processingFee!,
    installmentCount,
    currency,
    maturityDate,
    status,
    statusReason: optionalText(row['statusReason']),
    feeAmount: toAmount(row['feeAmount']),
    totalRepayable: toAmount(row['totalRepayable']),
    principalRepaid: required.principalRepaid!,
    feeRepaid: required.feeRepaid!,
    outstanding: required.outstanding!,
    penaltyAccrued: toAmount(row['penaltyAccrued']),
    penaltyRepaid: toAmount(row['penaltyRepaid']),
    penaltyOutstanding: toAmount(row['penaltyOutstanding']),
    overdue: typeof row['overdue'] === 'boolean' ? row['overdue'] : null,
    overdueAmount: toAmount(row['overdueAmount']),
    nextDueDate: optionalText(row['nextDueDate']),
    approvedAt: optionalText(row['approvedAt']),
    disbursedAt: optionalText(row['disbursedAt']),
    settledAt: optionalText(row['settledAt']),
    createdAt,
  };
}

function parseInstallment(row: unknown): Installment | null {
  if (!isObject(row)) {
    return null;
  }
  const amounts = {
    principalDue: toAmount(row['principalDue']),
    feeDue: toAmount(row['feeDue']),
    totalDue: toAmount(row['totalDue']),
    principalPaid: toAmount(row['principalPaid']),
    feePaid: toAmount(row['feePaid']),
    remaining: toAmount(row['remaining']),
  };
  const status = INSTALLMENT_STATUSES.find((s) => s === row['status']);
  const { sequenceNo, dueDate } = row;
  if (!isCount(sequenceNo) || !isText(dueDate) || !status || Object.values(amounts).some((value) => value === null)) {
    return null;
  }
  return {
    sequenceNo,
    dueDate,
    status,
    principalDue: amounts.principalDue!,
    feeDue: amounts.feeDue!,
    totalDue: amounts.totalDue!,
    principalPaid: amounts.principalPaid!,
    feePaid: amounts.feePaid!,
    remaining: amounts.remaining!,
    penaltyAccrued: toAmount(row['penaltyAccrued']),
    penaltyPaid: toAmount(row['penaltyPaid']),
    penaltyRemaining: toAmount(row['penaltyRemaining']),
    penaltyAccruedThrough: optionalText(row['penaltyAccruedThrough']),
  };
}

function parseDisbursement(row: unknown): Disbursement | null {
  if (!isObject(row)) {
    return null;
  }
  const amount = toAmount(row['amount']);
  const { id, agreementId, currency, status, reference, disbursedAt } = row;
  if (!isText(id) || !isText(agreementId) || !isText(currency) || !isText(status) || !isText(reference) || !isText(disbursedAt) || amount === null) {
    return null;
  }
  return { id, agreementId, amount, currency, status, reference, disbursedAt };
}

function toAmount(value: unknown): number | null {
  const amount = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  return typeof amount === 'number' && Number.isFinite(amount) ? amount : null;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
