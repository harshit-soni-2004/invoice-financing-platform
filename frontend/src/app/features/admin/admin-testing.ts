import { Page } from '../../shared/api-failure';
import { AdminUser } from './admin-users.service';
import { AuditEvent } from './audit-api.service';

export function userFixture(overrides: Partial<AdminUser> = {}): AdminUser {
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

export function auditFixture(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    id: 41,
    eventId: 'e0000000-0000-4000-8000-000000000041',
    eventType: 'InvoiceSubmitted',
    schemaVersion: 1,
    producer: 'invoice-service',
    aggregateType: 'Invoice',
    aggregateId: 'a0000000-0000-4000-8000-000000000001',
    actorId: '7d4c2f9e-0000-4000-8000-000000000001',
    correlationId: 'corr-1',
    occurredAt: '2026-10-28T09:00:00Z',
    recordedAt: '2026-10-28T09:00:01Z',
    payload: { invoiceNumber: 'INV-2026/014', status: 'SUBMITTED' },
    ...overrides,
  };
}

export function pageOf<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length > 0 ? 1 : 0, ...overrides };
}
