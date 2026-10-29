import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config';
import { ApiFailure, apiFailureFor, isCount, isObject, isText, parsePage, textOrNull } from '../../shared/api-failure';
import {
  AGREEMENT_STATUSES,
  Agreement,
  AgreementView,
  DISBURSED_STATUSES,
  Disbursement,
  INSTALLMENT_STATUSES,
  Installment,
  OFFER_STATUSES,
  Offer,
  Repayment,
  RepaymentResult,
} from './financing.models';

/**
 * The shared mapping, plus financing-service's 422 business rules, which carry a `code` and a short `detail` that is
 * safe to show (it never contains amounts of other parties, SQL or token contents).
 */
export type FinancingFailure = ApiFailure | { kind: 'rule'; code: string | null; message: string | null };

export class FinancingApiError extends Error {
  constructor(readonly failure: FinancingFailure) {
    super(`Financing request failed: ${failure.kind}`);
  }
}

export function financingFailureOf(error: unknown): FinancingFailure {
  return error instanceof FinancingApiError ? error.failure : { kind: 'unexpected' };
}

/** How many of the seller's agreements are scanned to find the one for an invoice (the API has no invoice filter). */
export const AGREEMENT_SCAN_PAGES = 10;
const AGREEMENT_SCAN_SIZE = 100;
const OFFERS_PAGE_SIZE = 100;

/**
 * The seller's side of financing-service, through the gateway. Requests never name a seller: financing-service scopes
 * offers and agreements to the user in the access token. financing-service sends no ETags; concurrent changes come
 * back as 409 instead.
 */
@Injectable({ providedIn: 'root' })
export class SellerFinancingApiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/financing`;

  /** Offers on one of the seller's invoices, newest first. `truncated` when more exist than one page holds. */
  async offersForInvoice(invoiceId: string): Promise<{ offers: Offer[]; truncated: boolean }> {
    const params = new HttpParams()
      .set('invoiceId', invoiceId)
      .set('page', 0)
      .set('size', OFFERS_PAGE_SIZE)
      .set('sort', 'createdAt,desc');
    const page = parsed(parsePage(await call(this.http.get<unknown>(`${this.url}/offers`, { params })), parseOffer));
    return { offers: page.content, truncated: page.totalElements > page.content.length };
  }

  /** Accepts the offer; financing-service creates the agreement (PENDING_APPROVAL) and closes competing offers. */
  async acceptOffer(offerId: string): Promise<Agreement> {
    return parsed(parseAgreement(await call(this.http.post<unknown>(`${this.url}/offers/${encodeURIComponent(offerId)}/accept`, null))));
  }

  async rejectOffer(offerId: string, reason: string | null): Promise<Offer> {
    const body = reason ? { reason } : null;
    return parsed(parseOffer(await call(this.http.post<unknown>(`${this.url}/offers/${encodeURIComponent(offerId)}/reject`, body))));
  }

  /**
   * The newest agreement for an invoice, or null. `GET /agreements` cannot filter by invoice, so the seller's agreements
   * are scanned newest first, up to {@link AGREEMENT_SCAN_PAGES} pages.
   */
  async agreementForInvoice(invoiceId: string): Promise<Agreement | null> {
    for (let page = 0; page < AGREEMENT_SCAN_PAGES; page++) {
      const params = new HttpParams().set('page', page).set('size', AGREEMENT_SCAN_SIZE).set('sort', 'createdAt,desc');
      const result = parsed(parsePage(await call(this.http.get<unknown>(`${this.url}/agreements`, { params })), parseAgreement));
      const match = result.content.find((agreement) => agreement.invoiceId === invoiceId);
      if (match) {
        return match;
      }
      if (page + 1 >= result.totalPages) {
        return null;
      }
    }
    return null;
  }

  /** The agreement with its overdue figures, and, once disbursed, its payout, schedule and repayments. */
  async agreementView(id: string): Promise<AgreementView> {
    const path = `${this.url}/agreements/${encodeURIComponent(id)}`;
    const agreement = parsed(parseAgreement(await call(this.http.get<unknown>(path))));
    if (!DISBURSED_STATUSES.includes(agreement.status)) {
      return { agreement, disbursement: null, schedule: [], repayments: [] };
    }
    const [disbursement, schedule, repayments] = await Promise.all([
      call(this.http.get<unknown>(`${path}/disbursement`)).then((body) => parsed(parseDisbursement(body))),
      call(this.http.get<unknown>(`${path}/schedule`)).then((body) => parsedList(body, parseInstallment)),
      call(this.http.get<unknown>(`${path}/repayments`)).then((body) => parsedList(body, parseRepayment)),
    ]);
    return { agreement, disbursement, schedule, repayments };
  }

  /**
   * Records a simulated repayment. Retrying the same repayment must reuse the same key: financing-service then answers
   * with the original record (200, `Idempotent-Replayed: true`) instead of recording it twice.
   */
  async recordRepayment(agreementId: string, amount: string, idempotencyKey: string): Promise<RepaymentResult> {
    const response = await call(
      this.http.post<unknown>(`${this.url}/agreements/${encodeURIComponent(agreementId)}/repayments`, { amount }, {
        observe: 'response',
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
      }),
    );
    return {
      repayment: parsed(parseRepayment(response.body)),
      replayed: response.headers.get('Idempotent-Replayed')?.trim() === 'true',
    };
  }
}

async function call<T>(request: Observable<T>): Promise<T> {
  try {
    return await firstValueFrom(request);
  } catch (error) {
    throw new FinancingApiError(error instanceof HttpErrorResponse ? financingFailureFor(error) : { kind: 'unexpected' });
  }
}

export function financingFailureFor(error: HttpErrorResponse): FinancingFailure {
  if (error.status === 422) {
    const body: Record<string, unknown> = isObject(error.error) ? error.error : {};
    const detail = body['detail'];
    return {
      kind: 'rule',
      code: textOrNull(body['code']),
      message: typeof detail === 'string' && detail.length > 0 && detail.length <= 300 ? detail : null,
    };
  }
  return apiFailureFor(error);
}

function parsed<T>(value: T | null): T {
  if (value === null) {
    throw new FinancingApiError({ kind: 'unexpected' });
  }
  return value;
}

function parsedList<T>(body: unknown, parseRow: (row: unknown) => T | null): T[] {
  if (!Array.isArray(body)) {
    throw new FinancingApiError({ kind: 'unexpected' });
  }
  return body.map((row) => parsed(parseRow(row)));
}

/** Reads every named amount; null if any is missing or not a number. */
function amounts<K extends string>(row: Record<string, unknown>, keys: readonly K[]): Record<K, number> | null {
  const result = {} as Record<K, number>;
  for (const key of keys) {
    const value = toAmount(row[key]);
    if (value === null) {
      return null;
    }
    result[key] = value;
  }
  return result;
}

export function parseOffer(row: unknown): Offer | null {
  if (!isObject(row)) {
    return null;
  }
  const money = amounts(row, ['advanceAmount', 'annualRate', 'processingFee'] as const);
  const status = OFFER_STATUSES.find((s) => s === row['status']);
  const { id, invoiceId, installmentCount, currency, expiresAt, createdAt } = row;
  if (
    !money ||
    !status ||
    !isText(id) ||
    !isText(invoiceId) ||
    !isText(currency) ||
    !isText(expiresAt) ||
    !isText(createdAt) ||
    !isCount(installmentCount)
  ) {
    return null;
  }
  return {
    id,
    invoiceId,
    ...money,
    installmentCount,
    currency,
    expiresAt,
    status,
    statusReason: textOrNull(row['statusReason']),
    decidedAt: textOrNull(row['decidedAt']),
    createdAt,
  };
}

export function parseAgreement(row: unknown): Agreement | null {
  if (!isObject(row)) {
    return null;
  }
  const money = amounts(row, [
    'invoiceAmount',
    'advanceAmount',
    'annualRate',
    'processingFee',
    'principalRepaid',
    'feeRepaid',
    'outstanding',
    'penaltyAccrued',
    'penaltyRepaid',
    'penaltyOutstanding',
  ] as const);
  const status = AGREEMENT_STATUSES.find((s) => s === row['status']);
  const { id, offerId, invoiceId, installmentCount, currency, maturityDate, createdAt } = row;
  if (
    !money ||
    !status ||
    !isText(id) ||
    !isText(offerId) ||
    !isText(invoiceId) ||
    !isText(currency) ||
    !isText(maturityDate) ||
    !isText(createdAt) ||
    !isCount(installmentCount)
  ) {
    return null;
  }
  return {
    id,
    offerId,
    invoiceId,
    ...money,
    installmentCount,
    currency,
    maturityDate,
    status,
    statusReason: textOrNull(row['statusReason']),
    feeAmount: toAmount(row['feeAmount']),
    totalRepayable: toAmount(row['totalRepayable']),
    overdue: typeof row['overdue'] === 'boolean' ? row['overdue'] : null,
    overdueAmount: toAmount(row['overdueAmount']),
    nextDueDate: textOrNull(row['nextDueDate']),
    approvedAt: textOrNull(row['approvedAt']),
    disbursedAt: textOrNull(row['disbursedAt']),
    settledAt: textOrNull(row['settledAt']),
    createdAt,
  };
}

function parseDisbursement(row: unknown): Disbursement | null {
  if (!isObject(row)) {
    return null;
  }
  const amount = toAmount(row['amount']);
  const { id, agreementId, currency, status, reference, disbursedAt } = row;
  if (
    amount === null ||
    !isText(id) ||
    !isText(agreementId) ||
    !isText(currency) ||
    !isText(status) ||
    !isText(reference) ||
    !isText(disbursedAt)
  ) {
    return null;
  }
  return { id, agreementId, amount, currency, status, reference, disbursedAt };
}

function parseInstallment(row: unknown): Installment | null {
  if (!isObject(row)) {
    return null;
  }
  const money = amounts(row, [
    'principalDue',
    'feeDue',
    'totalDue',
    'principalPaid',
    'feePaid',
    'remaining',
    'penaltyAccrued',
    'penaltyPaid',
    'penaltyRemaining',
  ] as const);
  const status = INSTALLMENT_STATUSES.find((s) => s === row['status']);
  const { sequenceNo, dueDate } = row;
  if (!money || !status || !isCount(sequenceNo) || !isText(dueDate)) {
    return null;
  }
  return { sequenceNo, dueDate, status, ...money, penaltyAccruedThrough: textOrNull(row['penaltyAccruedThrough']) };
}

function parseRepayment(row: unknown): Repayment | null {
  if (!isObject(row)) {
    return null;
  }
  const money = amounts(row, ['amount', 'principalComponent', 'feeComponent', 'penaltyComponent'] as const);
  const { id, agreementId, currency, receivedAt } = row;
  if (!money || !isText(id) || !isText(agreementId) || !isText(currency) || !isText(receivedAt)) {
    return null;
  }
  return { id, agreementId, ...money, currency, receivedAt };
}

function toAmount(value: unknown): number | null {
  const amount = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  return typeof amount === 'number' && Number.isFinite(amount) ? amount : null;
}
