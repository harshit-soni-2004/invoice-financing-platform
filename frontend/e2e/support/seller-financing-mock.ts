import { Page, Request, Route } from '@playwright/test';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, If-Match, Idempotency-Key',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
  'Access-Control-Expose-Headers': 'ETag, Location, Idempotent-Replayed, Retry-After',
};

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
  remaining: number;
  status: string;
  penaltyAccrued: number;
  penaltyPaid: number;
  penaltyRemaining: number;
  penaltyAccruedThrough: string | null;
}

export interface StoredRepayment {
  id: string;
  agreementId: string;
  amount: number;
  principalComponent: number;
  feeComponent: number;
  penaltyComponent: number;
  currency: string;
  receivedAt: string;
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
  penaltyAccrued: number;
  penaltyRepaid: number;
  schedule: StoredInstallment[];
  repayments: StoredRepayment[];
}

export interface FinancingMock {
  offers: StoredOffer[];
  agreements: StoredAgreement[];
  /** Every request the app sent to /api/financing, except CORS preflights. */
  requests: Request[];
  /** Answers the next request with this method and path suffix with the given status and body instead. */
  failNext(method: string, pathSuffix: string, status: number, body?: Record<string, unknown>): void;
  /** Processes the next repayment, then drops the connection, as if the response was lost on the way back. */
  dropNextRepaymentResponse(): void;
  setDelay(ms: number): void;
}

const cents = (value: number) => Math.round(value * 100);
const money = (value: number) => cents(value) / 100;

function outstanding(agreement: StoredAgreement): number {
  return money(agreement.schedule.reduce((sum, i) => sum + i.remaining, 0));
}

function agreementResponse(agreement: StoredAgreement, detailed: boolean) {
  const { schedule, repayments, ...rest } = agreement;
  void repayments;
  const overdueAmount = money(schedule.filter((i) => i.status === 'OVERDUE').reduce((sum, i) => sum + i.remaining, 0));
  return {
    ...rest,
    outstanding: outstanding(agreement),
    principalOutstanding: money(agreement.advanceAmount - agreement.principalRepaid),
    feeOutstanding: money((agreement.feeAmount ?? 0) - agreement.feeRepaid),
    penaltyOutstanding: money(agreement.penaltyAccrued - agreement.penaltyRepaid),
    overdue: detailed ? overdueAmount > 0 : null,
    overdueAmount: detailed ? overdueAmount : null,
    nextDueDate: detailed ? (schedule.find((i) => i.remaining > 0)?.dueDate ?? null) : null,
  };
}

/** Oldest installment first; penalty, then fee, then principal, as RepaymentPlan.allocate. */
function applyRepayment(agreement: StoredAgreement, amount: number): StoredRepayment {
  let left = cents(amount);
  const parts = { principal: 0, fee: 0, penalty: 0 };
  for (const i of agreement.schedule) {
    const penalty = Math.min(left, cents(i.penaltyRemaining));
    left -= penalty;
    const fee = Math.min(left, cents(i.feeDue - i.feePaid));
    left -= fee;
    const principal = Math.min(left, cents(i.principalDue - i.principalPaid));
    left -= principal;
    i.penaltyPaid = money(i.penaltyPaid + penalty / 100);
    i.penaltyRemaining = money(i.penaltyRemaining - penalty / 100);
    i.feePaid = money(i.feePaid + fee / 100);
    i.principalPaid = money(i.principalPaid + principal / 100);
    i.remaining = money(i.remaining - (penalty + fee + principal) / 100);
    if (i.remaining === 0) {
      i.status = 'PAID';
    } else if (penalty + fee + principal > 0 && i.status !== 'OVERDUE') {
      i.status = 'PARTIALLY_PAID';
    }
    parts.penalty += penalty;
    parts.fee += fee;
    parts.principal += principal;
  }
  agreement.principalRepaid = money(agreement.principalRepaid + parts.principal / 100);
  agreement.feeRepaid = money(agreement.feeRepaid + parts.fee / 100);
  agreement.penaltyRepaid = money(agreement.penaltyRepaid + parts.penalty / 100);
  const now = new Date().toISOString();
  agreement.status = outstanding(agreement) === 0 ? 'SETTLED' : 'REPAYMENT_IN_PROGRESS';
  if (agreement.status === 'SETTLED') {
    agreement.settledAt = now;
  }
  const repayment: StoredRepayment = {
    id: `r0000000-0000-4000-8000-${String(agreement.repayments.length + 1).padStart(12, '0')}`,
    agreementId: agreement.id,
    amount: money(amount),
    principalComponent: parts.principal / 100,
    feeComponent: parts.fee / 100,
    penaltyComponent: parts.penalty / 100,
    currency: 'INR',
    receivedAt: now,
  };
  agreement.repayments.push(repayment);
  return repayment;
}

/**
 * A small stand-in for financing-service behind the gateway, for one seller: offers with the seller's accept/reject
 * transitions (accepting creates a PENDING_APPROVAL agreement and closes competing offers), agreement reads, and
 * idempotent repayments keyed by (Idempotency-Key, agreement, amount) like IdempotentExecutor. Roles are not checked.
 */
export async function mockFinancing(
  page: Page,
  seed: { offers?: StoredOffer[]; agreements?: StoredAgreement[] } = {},
): Promise<FinancingMock> {
  const offers = [...(seed.offers ?? [])];
  const agreements = [...(seed.agreements ?? [])];
  const requests: Request[] = [];
  const failures: { method: string; suffix: string; status: number; body?: Record<string, unknown> }[] = [];
  const idempotency = new Map<string, { hash: string; repaymentId: string }>();
  let dropNextRepayment = false;
  let delay = 0;

  const reply = async (route: Route, status: number, body: unknown, headers: Record<string, string> = {}) => {
    if (delay > 0) {
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
    await route.fulfill({ status, headers: { ...CORS, ...headers }, json: body });
  };
  const problem = (status: number, code: string, extra: Record<string, unknown> = {}) => ({ status, code, detail: code, ...extra });
  const pageOf = <T>(content: T[], url: URL) => {
    const number = Number(url.searchParams.get('page') ?? 0);
    const size = Number(url.searchParams.get('size') ?? 20);
    return {
      content: content.slice(number * size, number * size + size),
      page: number,
      size,
      totalElements: content.length,
      totalPages: Math.ceil(content.length / size),
    };
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
    const path = url.pathname.replace('/api/financing', '');
    const failure = failures.findIndex((f) => f.method === request.method() && path.endsWith(f.suffix));
    if (failure >= 0) {
      const [next] = failures.splice(failure, 1);
      return reply(route, next.status, next.body ?? problem(next.status, 'FAILED'));
    }
    const [, resource, id, action] = path.split('/');

    if (resource === 'offers' && request.method() === 'GET' && !id) {
      const invoiceId = url.searchParams.get('invoiceId');
      const matching = offers.filter((o) => !invoiceId || o.invoiceId === invoiceId).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return reply(route, 200, pageOf(matching, url));
    }
    if (resource === 'offers' && request.method() === 'POST' && id && (action === 'accept' || action === 'reject')) {
      const offer = offers.find((o) => o.id === id);
      if (!offer) {
        return reply(route, 404, problem(404, 'OFFER_NOT_FOUND'));
      }
      const target = action === 'accept' ? 'ACCEPTED' : 'REJECTED';
      if (offer.status !== 'OFFERED') {
        return reply(route, 409, problem(409, 'INVALID_STATUS_TRANSITION', { currentStatus: offer.status, requestedStatus: target }));
      }
      if (Date.parse(offer.expiresAt) <= Date.now()) {
        return reply(route, 409, problem(409, 'OFFER_EXPIRED'));
      }
      const now = new Date().toISOString();
      if (action === 'reject') {
        const body = request.postData() ? (request.postDataJSON() as { reason?: string } | null) : null;
        Object.assign(offer, { status: 'REJECTED', statusReason: body?.reason?.trim() || null, decidedAt: now });
        return reply(route, 200, offer);
      }
      Object.assign(offer, { status: 'ACCEPTED', decidedAt: now });
      for (const other of offers.filter((o) => o.invoiceId === offer.invoiceId && o.status === 'OFFERED')) {
        Object.assign(other, { status: 'CLOSED', statusReason: 'Another offer for this invoice was accepted', decidedAt: now });
      }
      const agreement: StoredAgreement = {
        id: `e0000000-0000-4000-8000-${String(agreements.length + 1).padStart(12, '0')}`,
        offerId: offer.id,
        invoiceId: offer.invoiceId,
        sellerId: offer.sellerId,
        financierId: offer.financierId,
        invoiceAmount: 340000,
        advanceAmount: offer.advanceAmount,
        annualRate: offer.annualRate,
        processingFee: offer.processingFee,
        installmentCount: offer.installmentCount,
        currency: 'INR',
        maturityDate: '2026-12-15',
        status: 'PENDING_APPROVAL',
        statusReason: null,
        feeAmount: null,
        totalRepayable: null,
        principalRepaid: 0,
        feeRepaid: 0,
        approvedAt: null,
        disbursedAt: null,
        settledAt: null,
        createdAt: now,
        penaltyAccrued: 0,
        penaltyRepaid: 0,
        schedule: [],
        repayments: [],
      };
      agreements.push(agreement);
      return reply(route, 201, agreementResponse(agreement, false));
    }

    if (resource === 'agreements') {
      if (request.method() === 'GET' && !id) {
        const sorted = [...agreements].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        return reply(route, 200, pageOf(sorted.map((a) => agreementResponse(a, false)), url));
      }
      const agreement = agreements.find((a) => a.id === id);
      if (!agreement) {
        return reply(route, 404, problem(404, 'AGREEMENT_NOT_FOUND'));
      }
      if (request.method() === 'GET' && !action) {
        return reply(route, 200, agreementResponse(agreement, true));
      }
      if (request.method() === 'GET' && action === 'disbursement') {
        if (!agreement.disbursedAt) {
          return reply(route, 404, problem(404, 'DISBURSEMENT_NOT_FOUND'));
        }
        return reply(route, 200, {
          id: 'd1000000-0000-4000-8000-000000000001',
          agreementId: agreement.id,
          amount: agreement.advanceAmount,
          currency: 'INR',
          status: 'SIMULATED_COMPLETED',
          reference: 'SIM-0011223344556677',
          disbursedAt: agreement.disbursedAt,
        });
      }
      if (request.method() === 'GET' && action === 'schedule') {
        return reply(route, 200, agreement.schedule);
      }
      if (request.method() === 'GET' && action === 'repayments') {
        return reply(route, 200, agreement.repayments);
      }
      if (request.method() === 'POST' && action === 'repayments') {
        const key = (await request.headerValue('idempotency-key'))?.trim() ?? '';
        if (!key) {
          return reply(route, 400, problem(400, 'IDEMPOTENCY_KEY_REQUIRED'));
        }
        if (!/^[A-Za-z0-9_-]{8,100}$/.test(key)) {
          return reply(route, 400, problem(400, 'IDEMPOTENCY_KEY_INVALID'));
        }
        const body = request.postDataJSON() as { amount?: unknown };
        const amount = Number(body.amount);
        if (!Number.isFinite(amount) || amount < 0.01) {
          return reply(route, 400, problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'amount', message: 'must be greater than or equal to 0.01' }] }));
        }
        const hash = `${agreement.id}|${amount.toFixed(2)}`;
        const previous = idempotency.get(key);
        if (previous) {
          if (previous.hash !== hash) {
            return reply(route, 409, problem(409, 'IDEMPOTENCY_KEY_REUSED'));
          }
          const original = agreement.repayments.find((r) => r.id === previous.repaymentId)!;
          return reply(route, 200, original, { 'Idempotent-Replayed': 'true' });
        }
        if (agreement.status === 'SETTLED') {
          return reply(route, 409, problem(409, 'AGREEMENT_SETTLED'));
        }
        if (agreement.status !== 'DISBURSED' && agreement.status !== 'REPAYMENT_IN_PROGRESS') {
          return reply(route, 409, problem(409, 'REPAYMENT_NOT_ALLOWED'));
        }
        if (cents(amount) > cents(outstanding(agreement))) {
          return reply(route, 422, {
            ...problem(422, 'REPAYMENT_EXCEEDS_OUTSTANDING'),
            detail: 'The repayment is larger than the amount outstanding',
          });
        }
        const repayment = applyRepayment(agreement, amount);
        idempotency.set(key, { hash, repaymentId: repayment.id });
        if (dropNextRepayment) {
          dropNextRepayment = false;
          return route.abort('connectionreset');
        }
        return reply(route, 201, repayment, { 'Idempotent-Replayed': 'false' });
      }
    }
    return reply(route, 404, problem(404, 'NOT_FOUND'));
  };

  await page.route(/^http:\/\/localhost:8080\/api\/financing\/.*$/, handler);

  return {
    offers,
    agreements,
    requests,
    failNext: (method, suffix, status, body) => failures.push({ method, suffix, status, body }),
    dropNextRepaymentResponse: () => (dropNextRepayment = true),
    setDelay: (ms) => (delay = ms),
  };
}

export const SELLER_ID = '50000000-0000-4000-8000-000000000001';

export function storedOffer(overrides: Partial<StoredOffer>): StoredOffer {
  return {
    id: 'f0000000-0000-4000-8000-000000000001',
    invoiceId: 'b0000000-0000-4000-8000-000000000001',
    sellerId: SELLER_ID,
    financierId: '60000000-0000-4000-8000-000000000001',
    advanceAmount: 250000,
    annualRate: 0.12,
    processingFee: 1000,
    installmentCount: 2,
    currency: 'INR',
    expiresAt: new Date(Date.now() + 5 * 86_400_000).toISOString(),
    status: 'OFFERED',
    statusReason: null,
    decidedAt: null,
    createdAt: '2026-10-20T05:00:00Z',
    ...overrides,
  };
}

/** A disbursed agreement with two installments; the first is overdue with penalty interest. */
export function disbursedAgreement(overrides: Partial<StoredAgreement> = {}): StoredAgreement {
  return {
    id: 'e0000000-0000-4000-8000-000000000001',
    offerId: 'f0000000-0000-4000-8000-000000000001',
    invoiceId: 'b0000000-0000-4000-8000-000000000001',
    sellerId: SELLER_ID,
    financierId: '60000000-0000-4000-8000-000000000001',
    invoiceAmount: 340000,
    advanceAmount: 250000,
    annualRate: 0.12,
    processingFee: 1000,
    installmentCount: 2,
    currency: 'INR',
    maturityDate: '2026-12-15',
    status: 'DISBURSED',
    statusReason: null,
    feeAmount: 5000,
    totalRepayable: 255000,
    principalRepaid: 0,
    feeRepaid: 0,
    approvedAt: '2026-09-20T05:00:00Z',
    disbursedAt: '2026-09-21T05:00:00Z',
    settledAt: null,
    createdAt: '2026-09-19T05:00:00Z',
    penaltyAccrued: 210.5,
    penaltyRepaid: 0,
    schedule: [
      {
        sequenceNo: 1,
        dueDate: '2026-10-18',
        principalDue: 125000,
        feeDue: 2500,
        totalDue: 127500,
        principalPaid: 0,
        feePaid: 0,
        remaining: 127710.5,
        status: 'OVERDUE',
        penaltyAccrued: 210.5,
        penaltyPaid: 0,
        penaltyRemaining: 210.5,
        penaltyAccruedThrough: '2026-10-28',
      },
      {
        sequenceNo: 2,
        dueDate: '2026-12-15',
        principalDue: 125000,
        feeDue: 2500,
        totalDue: 127500,
        principalPaid: 0,
        feePaid: 0,
        remaining: 127500,
        status: 'PENDING',
        penaltyAccrued: 0,
        penaltyPaid: 0,
        penaltyRemaining: 0,
        penaltyAccruedThrough: null,
      },
    ],
    repayments: [],
    ...overrides,
  };
}
