import { Page, Request, Route } from '@playwright/test';

/** Must match public/config.json. */
const FINANCING_URL = 'http://localhost:8080/api/financing';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, Idempotency-Key',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Expose-Headers': 'Location, Idempotent-Replayed, Retry-After',
};

export interface StoredOpportunity {
  invoiceId: string;
  invoiceNumber: string;
  buyerName: string;
  amount: number;
  currency: string;
  issueDate: string;
  dueDate: string;
  daysToMaturity: number;
  maxAdvanceAmount: number;
}

export interface StoredOffer {
  id: string;
  invoiceId: string;
  sellerId: string;
  financierId: string;
  advanceAmount: number;
  annualRate: number;
  processingFee: number;
  installmentCount: number;
  currency: string;
  expiresAt: string;
  status: string;
  statusReason: string | null;
  decidedAt: string | null;
  createdAt: string;
}

export interface StoredInstallment {
  sequenceNo: number;
  dueDate: string;
  principalDue: number;
  feeDue: number;
  totalDue: number;
  principalPaid: number;
  feePaid: number;
  /** Includes unpaid penalty, as financing-service's `remaining` does. */
  remaining: number;
  status: string;
  penaltyAccrued?: number;
  penaltyPaid?: number;
  penaltyAccruedThrough?: string | null;
}

export interface StoredAgreement {
  id: string;
  offerId: string;
  invoiceId: string;
  sellerId: string;
  financierId: string;
  invoiceAmount: number;
  advanceAmount: number;
  annualRate: number;
  processingFee: number;
  installmentCount: number;
  currency: string;
  maturityDate: string;
  status: string;
  statusReason: string | null;
  feeAmount: number | null;
  totalRepayable: number | null;
  principalRepaid: number;
  feeRepaid: number;
  approvedAt: string | null;
  disbursedAt: string | null;
  settledAt: string | null;
  createdAt: string;
  schedule: StoredInstallment[];
  disbursement: Record<string, unknown> | null;
}

interface Failure {
  method: string;
  path: RegExp;
  status: number;
  body?: Record<string, unknown>;
  headers?: Record<string, string>;
  /** Carry out the request first, then answer with the failure (an answer lost on the way back). */
  afterProcessing?: boolean;
}

export interface FinancingMock {
  opportunities: StoredOpportunity[];
  offers: StoredOffer[];
  agreements: StoredAgreement[];
  /** Every request the app sent to /api/financing, except CORS preflights. */
  requests: Request[];
  /** Answers the next request with this method and a matching path with the failure instead of the fake's own behaviour. */
  failNext(failure: Failure): void;
  /** Delays every answer, to observe in-flight states. */
  setDelay(ms: number): void;
}

const FINANCIER_ID = '7d4c2f9e-0000-4000-8000-000000000001';
const SELLER_ID = '5e1d0000-0000-4000-8000-000000000001';

function isoDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

const cents = (value: number) => Math.round(value * 100) / 100;

/** financing-service's InstallmentResponse: penalty fields are always present. */
function installmentView(i: StoredInstallment) {
  const penaltyAccrued = i.penaltyAccrued ?? 0;
  const penaltyPaid = i.penaltyPaid ?? 0;
  return {
    ...i,
    penaltyAccrued,
    penaltyPaid,
    penaltyRemaining: cents(penaltyAccrued - penaltyPaid),
    penaltyAccruedThrough: i.penaltyAccruedThrough ?? null,
  };
}

function view(agreement: StoredAgreement, detail: boolean) {
  const { schedule, disbursement: _disbursement, ...rest } = agreement;
  const penaltyAccrued = cents(schedule.reduce((sum, i) => sum + (i.penaltyAccrued ?? 0), 0));
  const penaltyRepaid = cents(schedule.reduce((sum, i) => sum + (i.penaltyPaid ?? 0), 0));
  const penaltyOutstanding = cents(penaltyAccrued - penaltyRepaid);
  const outstanding =
    agreement.totalRepayable === null
      ? 0
      : cents(agreement.totalRepayable - agreement.principalRepaid - agreement.feeRepaid + penaltyOutstanding);
  const overdueAmount = schedule.filter((i) => i.status === 'OVERDUE').reduce((sum, i) => sum + i.remaining, 0);
  return {
    ...rest,
    outstanding,
    penaltyAccrued,
    penaltyRepaid,
    penaltyOutstanding,
    principalOutstanding: agreement.totalRepayable === null ? 0 : agreement.advanceAmount - agreement.principalRepaid,
    feeOutstanding: agreement.feeAmount === null ? 0 : agreement.feeAmount - agreement.feeRepaid,
    overdue: detail ? overdueAmount > 0 : null,
    overdueAmount: detail ? overdueAmount : null,
    nextDueDate: detail ? (schedule.find((i) => i.remaining > 0)?.dueDate ?? null) : null,
  };
}

/**
 * A small stand-in for financing-service behind the gateway, for one financier: opportunities, offers (one open offer
 * per invoice, advance cap), agreements with the approve and disburse steps, and Idempotency-Key replays on disburse.
 * financing-service sends no ETags, so neither does this. Register it after mockAuth so it takes precedence.
 */
export async function mockFinancing(
  page: Page,
  seed: { opportunities?: StoredOpportunity[]; offers?: StoredOffer[]; agreements?: StoredAgreement[] } = {},
): Promise<FinancingMock> {
  const opportunities = [...(seed.opportunities ?? [])];
  const offers = [...(seed.offers ?? [])];
  const agreements = [...(seed.agreements ?? [])];
  const requests: Request[] = [];
  const failures: Failure[] = [];
  const idempotency = new Map<string, string>();
  let delay = 0;
  let sequence = 0;

  const reply = async (route: Route, status: number, body: unknown, headers: Record<string, string> = {}) => {
    if (delay > 0) {
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
    await route.fulfill({ status, headers: { ...CORS, ...headers }, json: body });
  };
  const problem = (status: number, code: string, extra: Record<string, unknown> = {}) => ({ status, code, detail: code, ...extra });
  const page0 = <T>(items: T[], url: URL) => {
    const number = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 20);
    return {
      content: items.slice(number * size, number * size + size),
      page: number,
      size,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
    };
  };

  /** Runs the fake's own behaviour and returns the answer, without sending it. */
  const handle = async (request: Request, url: URL): Promise<[number, unknown, Record<string, string>?]> => {
    const parts = url.pathname.split('/').slice(3); // ['opportunities', id] | ['offers', id, 'withdraw'] | ...
    const [resource, id, action] = parts;
    const method = request.method();

    if (resource === 'opportunities' && method === 'GET') {
      if (!id) {
        const [field, direction] = (url.searchParams.get('sort') ?? 'dueDate,asc').split(',');
        const key = field as keyof StoredOpportunity;
        const sorted = [...opportunities].sort((a, b) => {
          const order = String(a[key]).localeCompare(String(b[key]), undefined, { numeric: true });
          return direction === 'desc' ? -order : order;
        });
        return [200, page0(sorted, url)];
      }
      const found = opportunities.find((o) => o.invoiceId === id);
      return found ? [200, found] : [404, problem(404, 'INVOICE_NOT_FOUND')];
    }

    if (resource === 'offers') {
      if (method === 'GET' && !id) {
        const invoiceId = url.searchParams.get('invoiceId');
        const mine = offers.filter((o) => !invoiceId || o.invoiceId === invoiceId).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        return [200, page0(mine, url)];
      }
      if (method === 'POST' && !id) {
        const body = request.postDataJSON() as Record<string, unknown>;
        const opportunity = opportunities.find((o) => o.invoiceId === body['invoiceId']);
        if (!opportunity) {
          return [404, problem(404, 'INVOICE_NOT_FOUND')];
        }
        if (Number(body['advanceAmount']) > opportunity.maxAdvanceAmount) {
          return [422, { ...problem(422, 'ADVANCE_TOO_HIGH'), detail: `advanceAmount may be at most ${opportunity.maxAdvanceAmount.toFixed(2)} for this invoice` }];
        }
        if (offers.some((o) => o.invoiceId === opportunity.invoiceId && o.status === 'OFFERED')) {
          return [409, problem(409, 'DUPLICATE_OPEN_OFFER')];
        }
        const offer: StoredOffer = {
          id: `f1000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`,
          invoiceId: opportunity.invoiceId,
          sellerId: SELLER_ID,
          financierId: FINANCIER_ID,
          advanceAmount: Number(body['advanceAmount']),
          annualRate: Number(body['annualRate']),
          processingFee: Number(body['processingFee'] ?? 0),
          installmentCount: Number(body['installmentCount']),
          currency: 'INR',
          expiresAt: String(body['expiresAt']),
          status: 'OFFERED',
          statusReason: null,
          decidedAt: null,
          createdAt: new Date().toISOString(),
        };
        offers.push(offer);
        return [201, offer, { Location: `${FINANCING_URL}/offers/${offer.id}` }];
      }
      const offer = offers.find((o) => o.id === id);
      if (method === 'POST' && offer && action === 'withdraw') {
        if (offer.status !== 'OFFERED') {
          return [409, problem(409, 'INVALID_STATUS_TRANSITION', { currentStatus: offer.status, requestedStatus: 'WITHDRAWN' })];
        }
        Object.assign(offer, { status: 'WITHDRAWN', decidedAt: new Date().toISOString() });
        return [200, offer];
      }
      return [404, problem(404, 'OFFER_NOT_FOUND')];
    }

    if (resource === 'agreements') {
      if (method === 'GET' && !id) {
        const status = url.searchParams.get('status');
        const mine = agreements.filter((a) => !status || a.status === status).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        return [200, { ...page0(mine, url), content: page0(mine, url).content.map((a) => view(a, false)) }];
      }
      const agreement = agreements.find((a) => a.id === id);
      if (!agreement) {
        return [404, problem(404, 'AGREEMENT_NOT_FOUND')];
      }
      if (method === 'GET' && !action) {
        return [200, view(agreement, true)];
      }
      if (method === 'GET' && action === 'schedule') {
        return [200, agreement.schedule.map(installmentView)];
      }
      if (method === 'GET' && action === 'disbursement') {
        return agreement.disbursement ? [200, agreement.disbursement] : [404, problem(404, 'DISBURSEMENT_NOT_FOUND')];
      }
      if (method === 'POST' && action === 'approve') {
        if (agreement.status !== 'PENDING_APPROVAL') {
          return [409, problem(409, 'INVALID_STATUS_TRANSITION', { currentStatus: agreement.status, requestedStatus: 'APPROVED' })];
        }
        Object.assign(agreement, { status: 'APPROVED', approvedAt: new Date().toISOString() });
        return [200, view(agreement, false)];
      }
      if (method === 'POST' && action === 'disburse') {
        const key = (await request.headerValue('idempotency-key'))?.trim() ?? '';
        if (!/^[A-Za-z0-9_-]{8,100}$/.test(key)) {
          return [400, problem(400, key ? 'IDEMPOTENCY_KEY_INVALID' : 'IDEMPOTENCY_KEY_REQUIRED')];
        }
        const previous = idempotency.get(key);
        if (previous !== undefined) {
          if (previous !== agreement.id) {
            return [409, problem(409, 'IDEMPOTENCY_KEY_REUSED')];
          }
          return [200, agreement.disbursement, { 'Idempotent-Replayed': 'true' }];
        }
        if (agreement.status !== 'APPROVED') {
          return [409, problem(409, 'INVALID_STATUS_TRANSITION', { currentStatus: agreement.status, requestedStatus: 'DISBURSED' })];
        }
        const now = new Date().toISOString();
        const fee = Math.round(((agreement.advanceAmount * agreement.annualRate * 60) / 365) * 100) / 100 + agreement.processingFee;
        const n = agreement.installmentCount;
        const principalPart = Math.floor((agreement.advanceAmount / n) * 100) / 100;
        const feePart = Math.floor((fee / n) * 100) / 100;
        agreement.schedule = Array.from({ length: n }, (_, i) => {
          const last = i === n - 1;
          const principalDue = last ? Math.round((agreement.advanceAmount - principalPart * (n - 1)) * 100) / 100 : principalPart;
          const feeDue = last ? Math.round((fee - feePart * (n - 1)) * 100) / 100 : feePart;
          const totalDue = Math.round((principalDue + feeDue) * 100) / 100;
          return { sequenceNo: i + 1, dueDate: isoDate(Math.ceil((60 * (i + 1)) / n)), principalDue, feeDue, totalDue, principalPaid: 0, feePaid: 0, remaining: totalDue, status: 'PENDING' };
        });
        agreement.disbursement = {
          id: `d1000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`,
          agreementId: agreement.id,
          amount: agreement.advanceAmount,
          currency: 'INR',
          status: 'SIMULATED',
          reference: `SIM-${String(sequence).padStart(6, '0')}`,
          disbursedAt: now,
        };
        Object.assign(agreement, { status: 'DISBURSED', disbursedAt: now, feeAmount: fee, totalRepayable: Math.round((agreement.advanceAmount + fee) * 100) / 100 });
        idempotency.set(key, agreement.id);
        return [201, agreement.disbursement, { 'Idempotent-Replayed': 'false' }];
      }
    }
    return [404, problem(404, 'NOT_FOUND')];
  };

  const handler = async (route: Route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    requests.push(request);
    if (!(await request.headerValue('authorization'))?.startsWith('Bearer mock-access-')) {
      return reply(route, 401, problem(401, 'UNAUTHENTICATED'));
    }
    const url = new URL(request.url());
    const index = failures.findIndex((f) => f.method === request.method() && f.path.test(url.pathname));
    if (index >= 0) {
      const [failure] = failures.splice(index, 1);
      if (failure.afterProcessing) {
        await handle(request, url);
      }
      return reply(route, failure.status, failure.body ?? problem(failure.status, 'FAILED'), failure.headers);
    }
    const [status, body, headers] = await handle(request, url);
    return reply(route, status, body, headers);
  };

  await page.route(/^http:\/\/localhost:8080\/api\/financing([/?].*)?$/, handler);

  return {
    opportunities,
    offers,
    agreements,
    requests,
    failNext: (failure) => failures.push(failure),
    setDelay: (ms) => (delay = ms),
  };
}

export function storedOpportunity(overrides: Partial<StoredOpportunity> = {}): StoredOpportunity {
  return {
    invoiceId: 'b0000000-0000-4000-8000-000000000001',
    invoiceNumber: 'INV-0001',
    buyerName: 'Kaveri Textiles Pvt Ltd',
    amount: 500000,
    currency: 'INR',
    issueDate: isoDate(-10),
    dueDate: isoDate(60),
    daysToMaturity: 60,
    maxAdvanceAmount: 490000,
    ...overrides,
  };
}

export function storedOffer(overrides: Partial<StoredOffer> = {}): StoredOffer {
  return {
    id: 'f0000000-0000-4000-8000-000000000001',
    invoiceId: 'b0000000-0000-4000-8000-000000000001',
    sellerId: SELLER_ID,
    financierId: FINANCIER_ID,
    advanceAmount: 450000,
    annualRate: 0.14,
    processingFee: 0,
    installmentCount: 1,
    currency: 'INR',
    expiresAt: new Date(Date.now() + 5 * 86_400_000).toISOString(),
    status: 'OFFERED',
    statusReason: null,
    decidedAt: null,
    createdAt: new Date(Date.now() - 3_600_000).toISOString(),
    ...overrides,
  };
}

export function storedAgreement(overrides: Partial<StoredAgreement> = {}): StoredAgreement {
  return {
    id: 'e0000000-0000-4000-8000-000000000001',
    offerId: 'f0000000-0000-4000-8000-000000000009',
    invoiceId: 'b0000000-0000-4000-8000-000000000009',
    sellerId: SELLER_ID,
    financierId: FINANCIER_ID,
    invoiceAmount: 500000,
    advanceAmount: 480000,
    annualRate: 0.12,
    processingFee: 1000,
    installmentCount: 2,
    currency: 'INR',
    maturityDate: isoDate(60),
    status: 'PENDING_APPROVAL',
    statusReason: null,
    feeAmount: null,
    totalRepayable: null,
    principalRepaid: 0,
    feeRepaid: 0,
    approvedAt: null,
    disbursedAt: null,
    settledAt: null,
    createdAt: '2026-10-28T09:00:00Z',
    schedule: [],
    disbursement: null,
    ...overrides,
  };
}
