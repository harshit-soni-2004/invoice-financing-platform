import { Page, Request, Route } from '@playwright/test';

/** URL patterns below must match the gateway in public/config.json (http://localhost:8080). */
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, If-Match',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
  'Access-Control-Expose-Headers': 'ETag, Location, Retry-After',
};

export interface MockApi<T> {
  rows: T[];
  /** Every request the app sent to this API, except CORS preflights. */
  requests: Request[];
  /** Answers the next request with this method using the given status and body instead of the fake's behaviour. */
  failNext(method: string, status: number, body?: Record<string, unknown>): void;
  setDelay(ms: number): void;
}

interface Handler<T> {
  (request: Request, url: URL, rows: T[]): { status: number; body: unknown };
}

async function mockApi<T>(page: Page, pattern: RegExp, seed: T[], handle: Handler<T>): Promise<MockApi<T>> {
  const rows = [...seed];
  const requests: Request[] = [];
  const failures: { method: string; status: number; body?: Record<string, unknown> }[] = [];
  let delay = 0;

  await page.route(pattern, async (route: Route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    requests.push(request);
    let status: number;
    let body: unknown;
    const failure = failures.findIndex((f) => f.method === request.method());
    if (!(await request.headerValue('authorization'))?.startsWith('Bearer mock-access-')) {
      [status, body] = [401, { status: 401, code: 'UNAUTHENTICATED' }];
    } else if (failure >= 0) {
      const [next] = failures.splice(failure, 1);
      [status, body] = [next.status, next.body ?? { status: next.status, code: 'FAILED', detail: 'internal detail' }];
    } else {
      ({ status, body } = handle(request, new URL(request.url()), rows));
    }
    if (delay > 0) {
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
    await route.fulfill({ status, headers: CORS, json: body });
  });

  return {
    rows,
    requests,
    failNext: (method, status, body) => failures.push({ method, status, body }),
    setDelay: (ms) => (delay = ms),
  };
}

function paged<T>(url: URL, matching: T[]) {
  const page = Number(url.searchParams.get('page') ?? 0);
  const size = Number(url.searchParams.get('size') ?? 20);
  return {
    content: matching.slice(page * size, page * size + size),
    page,
    size,
    totalElements: matching.length,
    totalPages: Math.ceil(matching.length / size),
  };
}

export interface StoredUser {
  id: string;
  email: string;
  displayName: string;
  role: 'SELLER' | 'FINANCIER' | 'ADMIN';
  status: 'ACTIVE' | 'PENDING_APPROVAL' | 'DISABLED';
  createdAt: string;
}

export function storedUser(overrides: Partial<StoredUser> = {}): StoredUser {
  return {
    id: 'f0000000-0000-4000-8000-000000000001',
    email: 'ravi@example.test',
    displayName: 'Ravi Menon',
    role: 'FINANCIER',
    status: 'PENDING_APPROVAL',
    createdAt: '2026-10-27T08:00:00Z',
    ...overrides,
  };
}

/** auth-service's admin user endpoints: list with role/status filters, and activation of pending accounts. */
export function mockAdminUsers(page: Page, seed: StoredUser[]): Promise<MockApi<StoredUser>> {
  return mockApi(page, /^http:\/\/localhost:8080\/api\/admin\/users([/?].*)?$/, seed, (request, url, users) => {
    const [, , , , id, action] = url.pathname.split('/');
    if (request.method() === 'GET' && !id) {
      const role = url.searchParams.get('role');
      const status = url.searchParams.get('status');
      const matching = users
        .filter((u) => (!role || u.role === role) && (!status || u.status === status))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return { status: 200, body: paged(url, matching) };
    }
    const user = users.find((u) => u.id === id);
    if (request.method() === 'POST' && action === 'activate') {
      if (!user) {
        return { status: 404, body: { status: 404, code: 'USER_NOT_FOUND' } };
      }
      if (user.status !== 'PENDING_APPROVAL') {
        return { status: 409, body: { status: 409, code: 'USER_NOT_PENDING_APPROVAL', currentStatus: user.status } };
      }
      user.status = 'ACTIVE';
      return { status: 200, body: user };
    }
    return { status: 404, body: { status: 404, code: 'NOT_FOUND' } };
  });
}

export interface StoredAuditEvent {
  id: number;
  eventId: string;
  eventType: string;
  schemaVersion: number;
  producer: string;
  aggregateType: string;
  aggregateId: string;
  actorId: string | null;
  correlationId: string | null;
  occurredAt: string;
  recordedAt: string;
  payload: unknown;
}

export function storedAuditEvent(overrides: Partial<StoredAuditEvent> = {}): StoredAuditEvent {
  return {
    id: 1,
    eventId: 'e0000000-0000-4000-8000-000000000001',
    eventType: 'InvoiceSubmitted',
    schemaVersion: 1,
    producer: 'invoice-service',
    aggregateType: 'Invoice',
    aggregateId: 'b0000000-0000-4000-8000-000000000001',
    actorId: '7d4c2f9e-0000-4000-8000-000000000009',
    correlationId: 'corr-1',
    occurredAt: '2026-10-28T09:00:00Z',
    recordedAt: '2026-10-28T09:00:01Z',
    payload: { invoiceNumber: 'INV-0001' },
    ...overrides,
  };
}

/** audit-service's search: exact-match filters, `from` inclusive and `to` exclusive, newest first. */
export function mockAudit(page: Page, seed: StoredAuditEvent[]): Promise<MockApi<StoredAuditEvent>> {
  return mockApi(page, /^http:\/\/localhost:8080\/api\/audit-events([/?].*)?$/, seed, (request, url, events) => {
    if (request.method() !== 'GET') {
      return { status: 405, body: { status: 405 } };
    }
    const p = url.searchParams;
    const from = p.get('from');
    const to = p.get('to');
    const matching = events
      .filter(
        (e) =>
          (!p.get('eventType') || e.eventType === p.get('eventType')) &&
          (!p.get('aggregateType') || e.aggregateType === p.get('aggregateType')) &&
          (!p.get('aggregateId') || e.aggregateId === p.get('aggregateId')) &&
          (!p.get('actorId') || e.actorId === p.get('actorId')) &&
          (!from || Date.parse(e.occurredAt) >= Date.parse(from)) &&
          (!to || Date.parse(e.occurredAt) < Date.parse(to)),
      )
      .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt) || b.id - a.id);
    return { status: 200, body: paged(url, matching) };
  });
}

export interface StoredNotification {
  id: string;
  eventType: string;
  subject: string;
  status: 'SENT' | 'SKIPPED';
  skipReason: string | null;
  createdAt: string;
  sentAt: string | null;
}

export function storedNotification(overrides: Partial<StoredNotification> = {}): StoredNotification {
  return {
    id: 'n0000000-0000-4000-8000-000000000001',
    eventType: 'InvoiceVerified',
    subject: 'Invoice INV-0001 verified',
    status: 'SENT',
    skipReason: null,
    createdAt: '2026-10-28T09:00:00Z',
    sentAt: '2026-10-28T09:00:05Z',
    ...overrides,
  };
}

/** notification-service's list of the caller's notifications, newest first. Register after mockAuth. */
export function mockNotifications(page: Page, seed: StoredNotification[]): Promise<MockApi<StoredNotification>> {
  return mockApi(page, /^http:\/\/localhost:8080\/api\/notifications([/?].*)?$/, seed, (request, url, rows) => {
    if (request.method() !== 'GET' || url.pathname !== '/api/notifications') {
      return { status: 404, body: { status: 404 } };
    }
    return { status: 200, body: paged(url, [...rows].sort((a, b) => b.createdAt.localeCompare(a.createdAt))) };
  });
}
