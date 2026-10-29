import { Page, Request, Route } from '@playwright/test';

/** Must match public/config.json. */
const INVOICES_URL = 'http://localhost:8080/api/invoices';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, If-Match',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
  'Access-Control-Expose-Headers': 'ETag, Location, Retry-After',
};

export interface StoredInvoice {
  id: string;
  invoiceNumber: string;
  buyerName: string;
  buyerGstin: string | null;
  description: string | null;
  amount: number;
  currency: string;
  issueDate: string;
  dueDate: string;
  status: string;
  statusReason: string | null;
  submittedAt: string | null;
  buyerConfirmedAt: string | null;
  verifiedAt: string | null;
  financedAt: string | null;
  settledAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InvoiceMock {
  invoices: StoredInvoice[];
  /** Every request the app sent to /api/invoices, except CORS preflights. */
  requests: Request[];
  /** Answers the next matching request with this status and body instead of the fake's own behaviour. */
  failNext(method: string, status: number, body?: Record<string, unknown>): void;
  /** Delays every answer, to observe in-flight states. */
  setDelay(ms: number): void;
}

const TRANSITIONS: Record<string, [from: string[], to: string, stamp: keyof StoredInvoice | null]> = {
  submit: [['DRAFT'], 'SUBMITTED', 'submittedAt'],
  'list-for-financing': [['VERIFIED'], 'FINANCING_AVAILABLE', null],
  'buyer-confirmation': [['SUBMITTED'], 'BUYER_CONFIRMED', 'buyerConfirmedAt'],
  verify: [['BUYER_CONFIRMED'], 'VERIFIED', 'verifiedAt'],
  reject: [['SUBMITTED', 'BUYER_CONFIRMED'], 'REJECTED', null],
};

function summary({ id, invoiceNumber, buyerName, amount, currency, dueDate, status, createdAt }: StoredInvoice) {
  return { id, invoiceNumber, buyerName, amount, currency, dueDate, status, createdAt };
}

/**
 * A small stand-in for invoice-service behind the gateway: one seller's invoices in memory, strong ETags carrying the
 * version, If-Match checks, the seller's DRAFT -> SUBMITTED and VERIFIED -> FINANCING_AVAILABLE transitions, and the
 * administrator's buyer confirmation, verification and rejection (with a required reason). Roles are not checked.
 * Register it after mockAuth so it takes precedence for /api/invoices.
 */
export async function mockInvoices(page: Page, seed: StoredInvoice[] = []): Promise<InvoiceMock> {
  const invoices = [...seed];
  const requests: Request[] = [];
  const failures: { method: string; status: number; body?: Record<string, unknown> }[] = [];
  let delay = 0;
  let sequence = 0;

  const reply = async (route: Route, status: number, body: unknown, headers: Record<string, string> = {}) => {
    if (delay > 0) {
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
    await route.fulfill({ status, headers: { ...CORS, ...headers }, json: body });
  };
  const problem = (status: number, code: string, extra: Record<string, unknown> = {}) => ({
    status,
    code,
    detail: code,
    ...extra,
  });

  const handler = async (route: Route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    requests.push(request);
    if (!(await request.headerValue('authorization'))?.startsWith('Bearer mock-access-')) {
      return reply(route, 401, problem(401, 'UNAUTHENTICATED'));
    }
    const failure = failures.findIndex((f) => f.method === request.method());
    if (failure >= 0) {
      const [next] = failures.splice(failure, 1);
      return reply(route, next.status, next.body ?? problem(next.status, 'FAILED'));
    }

    const url = new URL(request.url());
    const [, , , id, action] = url.pathname.split('/');
    const found = id ? invoices.find((invoice) => invoice.id === id) : undefined;

    if (request.method() === 'GET' && !id) {
      const status = url.searchParams.get('status');
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      const matching = invoices
        .filter((invoice) => !status || invoice.status === status)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return reply(route, 200, {
        content: matching.slice(page * size, page * size + size).map(summary),
        page,
        size,
        totalElements: matching.length,
        totalPages: Math.ceil(matching.length / size),
      });
    }
    if (request.method() === 'GET' && found && !action) {
      return reply(route, 200, found, { ETag: `"${found.version}"` });
    }
    if (request.method() === 'POST' && !id) {
      const body = request.postDataJSON() as Record<string, unknown>;
      if (invoices.some((invoice) => invoice.invoiceNumber === body['invoiceNumber'])) {
        return reply(route, 409, problem(409, 'DUPLICATE_INVOICE_NUMBER'));
      }
      const now = new Date(Date.UTC(2026, 9, 29, 3, 0, sequence)).toISOString();
      const invoice: StoredInvoice = {
        id: `c0000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`,
        invoiceNumber: String(body['invoiceNumber']),
        buyerName: String(body['buyerName']),
        buyerGstin: (body['buyerGstin'] as string | null) ?? null,
        description: (body['description'] as string | null) ?? null,
        amount: Number(body['amount']),
        currency: 'INR',
        issueDate: String(body['issueDate']),
        dueDate: String(body['dueDate']),
        status: 'DRAFT',
        statusReason: null,
        submittedAt: null,
        buyerConfirmedAt: null,
        verifiedAt: null,
        financedAt: null,
        settledAt: null,
        version: 0,
        createdAt: now,
        updatedAt: now,
      };
      invoices.push(invoice);
      return reply(route, 201, invoice, { ETag: '"0"', Location: `${INVOICES_URL}/${invoice.id}` });
    }
    if (request.method() === 'POST' && found && action && TRANSITIONS[action]) {
      const ifMatch = await request.headerValue('if-match');
      if (ifMatch && ifMatch !== `"${found.version}"`) {
        return reply(route, 412, problem(412, 'VERSION_MISMATCH', { currentETag: `"${found.version}"` }));
      }
      const [from, to, stamp] = TRANSITIONS[action];
      if (!from.includes(found.status)) {
        return reply(route, 409, problem(409, 'INVALID_STATUS_TRANSITION', { currentStatus: found.status, requestedStatus: to }));
      }
      let reason: string | null = null;
      if (action === 'reject') {
        const body = request.postDataJSON() as { reason?: unknown } | null;
        reason = typeof body?.reason === 'string' ? body.reason.trim() : '';
        if (!reason || reason.length > 500) {
          return reply(route, 400, problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'reason', message: 'must not be blank' }] }));
        }
      }
      const now = new Date().toISOString();
      Object.assign(
        found,
        { status: to, version: found.version + 1, updatedAt: now },
        stamp ? { [stamp]: now } : {},
        reason ? { statusReason: reason } : {},
      );
      return reply(route, 200, found, { ETag: `"${found.version}"` });
    }
    return reply(route, 404, problem(404, 'INVOICE_NOT_FOUND'));
  };

  await page.route(/^http:\/\/localhost:8080\/api\/invoices([/?].*)?$/, handler);

  return {
    invoices,
    requests,
    failNext: (method, status, body) => failures.push({ method, status, body }),
    setDelay: (ms) => (delay = ms),
  };
}

export function storedInvoice(overrides: Partial<StoredInvoice>): StoredInvoice {
  return {
    id: 'b0000000-0000-4000-8000-000000000001',
    invoiceNumber: 'INV-0001',
    buyerName: 'Kaveri Textiles Pvt Ltd',
    buyerGstin: null,
    description: null,
    amount: 340000,
    currency: 'INR',
    issueDate: '2026-10-01',
    dueDate: '2026-12-15',
    status: 'DRAFT',
    statusReason: null,
    submittedAt: null,
    buyerConfirmedAt: null,
    verifiedAt: null,
    financedAt: null,
    settledAt: null,
    version: 0,
    createdAt: '2026-10-01T05:00:00Z',
    updatedAt: '2026-10-01T05:00:00Z',
    ...overrides,
  };
}
