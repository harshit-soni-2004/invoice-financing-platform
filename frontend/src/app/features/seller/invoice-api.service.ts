import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config';
import {
  INVOICE_STATUSES,
  Invoice,
  InvoiceDraft,
  InvoiceField,
  InvoicePage,
  InvoiceStatus,
  InvoiceSummary,
  SellerAction,
  VersionedInvoice,
} from './invoice.models';

export const PAGE_SIZE = 20;

export type InvoiceFailure =
  | { kind: 'invalid'; fieldErrors: Partial<Record<InvoiceField, string>> }
  /** A business rule (amount limit, dates) refused the request; `message` is invoice-service's own wording. */
  | { kind: 'rule'; field: InvoiceField | null; message: string | null }
  | { kind: 'duplicate-number' }
  | { kind: 'invalid-transition'; currentStatus: InvoiceStatus | null }
  | { kind: 'not-editable' }
  | { kind: 'concurrent' }
  | { kind: 'stale' }
  | { kind: 'unauthenticated' }
  | { kind: 'forbidden' }
  | { kind: 'not-found' }
  | { kind: 'rate-limited'; retryAfterSeconds: number | null }
  | { kind: 'network' }
  | { kind: 'unavailable' }
  | { kind: 'unexpected' };

export class InvoiceApiError extends Error {
  constructor(readonly failure: InvoiceFailure) {
    super(`Invoice request failed: ${failure.kind}`);
  }
}

const FIELDS: readonly InvoiceField[] = [
  'invoiceNumber',
  'buyerName',
  'buyerGstin',
  'description',
  'amount',
  'issueDate',
  'dueDate',
];

/** invoice-service's 422 codes and the field each one is about. */
const RULE_FIELDS: Readonly<Record<string, InvoiceField>> = {
  AMOUNT_NOT_POSITIVE: 'amount',
  AMOUNT_TOO_LARGE: 'amount',
  ISSUE_DATE_IN_FUTURE: 'issueDate',
  DUE_DATE_NOT_AFTER_ISSUE_DATE: 'dueDate',
  TENOR_TOO_LONG: 'dueDate',
  DUE_DATE_TOO_SOON: 'dueDate',
};

const STRONG_ETAG = /^"\d{1,18}"$/;

/**
 * The seller's side of invoice-service, through the gateway. Requests never name a seller: invoice-service scopes
 * every call to the seller in the access token, which the auth interceptor attaches.
 */
@Injectable({ providedIn: 'root' })
export class InvoiceApiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/invoices`;

  /** `page` is zero-based, as in the API. */
  async list(page: number, status: InvoiceStatus | null): Promise<InvoicePage> {
    let params = new HttpParams().set('page', page).set('size', PAGE_SIZE).set('sort', 'createdAt,desc');
    if (status) {
      params = params.set('status', status);
    }
    const body = await call(this.http.get<unknown>(this.url, { params }));
    const result = parsePage(body);
    if (!result) {
      throw new InvoiceApiError({ kind: 'unexpected' });
    }
    return result;
  }

  async get(id: string): Promise<VersionedInvoice> {
    return versioned(await call(this.http.get<unknown>(`${this.url}/${encodeURIComponent(id)}`, { observe: 'response' })));
  }

  async create(draft: InvoiceDraft): Promise<VersionedInvoice> {
    return versioned(await call(this.http.post<unknown>(this.url, draft, { observe: 'response' })));
  }

  /** Runs a lifecycle action only if the invoice is still at the version the seller was looking at. */
  async transition(action: SellerAction, id: string, etag: string): Promise<VersionedInvoice> {
    const response = await call(
      this.http.post<unknown>(`${this.url}/${encodeURIComponent(id)}/${action}`, null, {
        observe: 'response',
        headers: new HttpHeaders({ 'If-Match': etag }),
      }),
    );
    return versioned(response);
  }
}

async function call<T>(request: Observable<T>): Promise<T> {
  try {
    return await firstValueFrom(request);
  } catch (error) {
    throw new InvoiceApiError(error instanceof HttpErrorResponse ? failureFor(error) : { kind: 'unexpected' });
  }
}

function versioned(response: HttpResponse<unknown>): VersionedInvoice {
  const invoice = parseInvoice(response.body);
  if (!invoice) {
    throw new InvoiceApiError({ kind: 'unexpected' });
  }
  const header = response.headers.get('ETag')?.trim();
  // invoice-service's ETag is the quoted version, so the body can stand in if a proxy dropped the header.
  return { invoice, etag: header && STRONG_ETAG.test(header) ? header : `"${invoice.version}"` };
}

function failureFor(error: HttpErrorResponse): InvoiceFailure {
  const body: Record<string, unknown> = isObject(error.error) ? error.error : {};
  const code = body['code'];
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
        case 'DUPLICATE_INVOICE_NUMBER':
          return { kind: 'duplicate-number' };
        case 'INVALID_STATUS_TRANSITION':
          return { kind: 'invalid-transition', currentStatus: statusOf(body['currentStatus']) };
        case 'INVOICE_NOT_EDITABLE':
          return { kind: 'not-editable' };
        case 'CONCURRENT_MODIFICATION':
          return { kind: 'concurrent' };
        default:
          return { kind: 'unexpected' };
      }
    case 412:
    case 428:
      return { kind: 'stale' };
    case 422: {
      const detail = body['detail'];
      return {
        kind: 'rule',
        field: typeof code === 'string' ? (RULE_FIELDS[code] ?? null) : null,
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

/** Reads `errors: [{ field, message }]` from the problem details, keeping only fields the form has. */
function fieldErrors(errors: unknown): Partial<Record<InvoiceField, string>> {
  const result: Partial<Record<InvoiceField, string>> = {};
  if (!Array.isArray(errors)) {
    return result;
  }
  for (const entry of errors) {
    if (!isObject(entry)) {
      continue;
    }
    const field = FIELDS.find((name) => name === entry['field']);
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

function parsePage(body: unknown): InvoicePage | null {
  if (!isObject(body) || !Array.isArray(body['content'])) {
    return null;
  }
  const { page, size, totalElements, totalPages } = body;
  if (!isCount(page) || !isCount(size) || !isCount(totalElements) || !isCount(totalPages)) {
    return null;
  }
  const content: InvoiceSummary[] = [];
  for (const row of body['content']) {
    const summary = parseSummary(row);
    if (!summary) {
      return null;
    }
    content.push(summary);
  }
  return { content, page, size, totalElements, totalPages };
}

function parseSummary(row: unknown): InvoiceSummary | null {
  if (!isObject(row)) {
    return null;
  }
  const amount = toAmount(row['amount']);
  const status = statusOf(row['status']);
  const { id, invoiceNumber, buyerName, currency, dueDate, createdAt } = row;
  if (
    !isText(id) ||
    !isText(invoiceNumber) ||
    !isText(buyerName) ||
    !isText(currency) ||
    !isText(dueDate) ||
    !isText(createdAt) ||
    amount === null ||
    status === null
  ) {
    return null;
  }
  return { id, invoiceNumber, buyerName, amount, currency, dueDate, status, createdAt };
}

function parseInvoice(body: unknown): Invoice | null {
  const summary = parseSummary(body);
  if (!summary || !isObject(body)) {
    return null;
  }
  const { issueDate, version, updatedAt } = body;
  if (!isText(issueDate) || !isCount(version) || !isText(updatedAt)) {
    return null;
  }
  return {
    ...summary,
    issueDate,
    version,
    updatedAt,
    buyerGstin: optionalText(body['buyerGstin']),
    description: optionalText(body['description']),
    statusReason: optionalText(body['statusReason']),
    submittedAt: optionalText(body['submittedAt']),
    buyerConfirmedAt: optionalText(body['buyerConfirmedAt']),
    verifiedAt: optionalText(body['verifiedAt']),
    financedAt: optionalText(body['financedAt']),
    settledAt: optionalText(body['settledAt']),
  };
}

function statusOf(value: unknown): InvoiceStatus | null {
  return INVOICE_STATUSES.find((status) => status === value) ?? null;
}

function toAmount(value: unknown): number | null {
  const amount = typeof value === 'string' ? Number(value) : value;
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
