import { Invoice, InvoicePage, InvoiceSummary, VersionedInvoice } from './invoice.models';

export function invoiceFixture(overrides: Partial<Invoice> = {}): Invoice {
  return {
    id: 'a0000000-0000-4000-8000-000000000001',
    invoiceNumber: 'INV-2026/014',
    buyerName: 'Kaveri Textiles Pvt Ltd',
    buyerGstin: '27AAPFU0939F1ZV',
    description: 'Cotton fabric rolls',
    amount: 125000.5,
    currency: 'INR',
    issueDate: '2026-10-20',
    dueDate: '2026-12-19',
    status: 'DRAFT',
    statusReason: null,
    submittedAt: null,
    buyerConfirmedAt: null,
    verifiedAt: null,
    financedAt: null,
    settledAt: null,
    version: 0,
    createdAt: '2026-10-28T09:00:00Z',
    updatedAt: '2026-10-28T09:00:00Z',
    ...overrides,
  };
}

export function versioned(overrides: Partial<Invoice> = {}): VersionedInvoice {
  const invoice = invoiceFixture(overrides);
  return { invoice, etag: `"${invoice.version}"` };
}

export function summaryFixture(overrides: Partial<InvoiceSummary> = {}): InvoiceSummary {
  const { id, invoiceNumber, buyerName, amount, currency, dueDate, status, createdAt } = invoiceFixture();
  return { id, invoiceNumber, buyerName, amount, currency, dueDate, status, createdAt, ...overrides };
}

export function pageOf(content: InvoiceSummary[], overrides: Partial<InvoicePage> = {}): InvoicePage {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length > 0 ? 1 : 0, ...overrides };
}
