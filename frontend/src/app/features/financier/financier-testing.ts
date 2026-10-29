import { Agreement, Disbursement, Installment, Offer, Opportunity, Page } from './financier.models';

export const INVOICE_ID = 'a0000000-0000-4000-8000-000000000001';
export const AGREEMENT_ID = 'e0000000-0000-4000-8000-000000000001';

function isoDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function opportunityFixture(overrides: Partial<Opportunity> = {}): Opportunity {
  return {
    invoiceId: INVOICE_ID,
    invoiceNumber: 'INV-2026/014',
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

export function offerFixture(overrides: Partial<Offer> = {}): Offer {
  return {
    id: 'f0000000-0000-4000-8000-000000000001',
    invoiceId: INVOICE_ID,
    advanceAmount: 480000,
    annualRate: 0.12,
    processingFee: 1000,
    installmentCount: 2,
    currency: 'INR',
    expiresAt: new Date(Date.now() + 5 * 86_400_000).toISOString(),
    status: 'OFFERED',
    statusReason: null,
    decidedAt: null,
    createdAt: '2026-10-28T09:00:00Z',
    ...overrides,
  };
}

export function agreementFixture(overrides: Partial<Agreement> = {}): Agreement {
  return {
    id: AGREEMENT_ID,
    offerId: 'f0000000-0000-4000-8000-000000000001',
    invoiceId: INVOICE_ID,
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
    outstanding: 0,
    overdue: false,
    overdueAmount: 0,
    nextDueDate: null,
    approvedAt: null,
    disbursedAt: null,
    settledAt: null,
    createdAt: '2026-10-28T09:00:00Z',
    ...overrides,
  };
}

/** An agreement after disbursement, with its fee fixed. */
export function disbursedAgreement(overrides: Partial<Agreement> = {}): Agreement {
  return agreementFixture({
    status: 'DISBURSED',
    feeAmount: 10468.49,
    totalRepayable: 490468.49,
    outstanding: 490468.49,
    approvedAt: '2026-10-28T10:00:00Z',
    disbursedAt: '2026-10-28T11:00:00Z',
    nextDueDate: isoDate(30),
    ...overrides,
  });
}

export function installmentFixture(overrides: Partial<Installment> = {}): Installment {
  return {
    sequenceNo: 1,
    dueDate: isoDate(30),
    principalDue: 240000,
    feeDue: 5234.24,
    totalDue: 245234.24,
    principalPaid: 0,
    feePaid: 0,
    remaining: 245234.24,
    status: 'PENDING',
    ...overrides,
  };
}

export function disbursementFixture(overrides: Partial<Disbursement> = {}): Disbursement {
  return {
    id: 'd0000000-0000-4000-8000-000000000001',
    agreementId: AGREEMENT_ID,
    amount: 480000,
    currency: 'INR',
    status: 'SIMULATED',
    reference: 'SIM-DISB-0001',
    disbursedAt: '2026-10-28T11:00:00Z',
    ...overrides,
  };
}

export function pageOf<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length > 0 ? 1 : 0, ...overrides };
}
