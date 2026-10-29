import { Agreement, AgreementView, Disbursement, Installment, Offer, Repayment } from './financing.models';

export const INVOICE_ID = 'a0000000-0000-4000-8000-000000000001';
export const AGREEMENT_ID = 'e0000000-0000-4000-8000-000000000001';

/** A date `days` from now, as financing-service sends instants. */
export function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString();
}

export function offerFixture(overrides: Partial<Offer> = {}): Offer {
  return {
    id: 'f0000000-0000-4000-8000-000000000001',
    invoiceId: INVOICE_ID,
    advanceAmount: 100000,
    annualRate: 0.12,
    processingFee: 500,
    installmentCount: 2,
    currency: 'INR',
    expiresAt: inDays(5),
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
    invoiceAmount: 125000.5,
    advanceAmount: 100000,
    annualRate: 0.12,
    processingFee: 500,
    installmentCount: 2,
    currency: 'INR',
    maturityDate: '2026-12-19',
    status: 'DISBURSED',
    statusReason: null,
    feeAmount: 2472.6,
    totalRepayable: 102472.6,
    principalRepaid: 0,
    feeRepaid: 0,
    outstanding: 102472.6,
    overdue: false,
    overdueAmount: 0,
    nextDueDate: '2026-11-24',
    approvedAt: '2026-10-28T10:00:00Z',
    disbursedAt: '2026-10-28T11:00:00Z',
    settledAt: null,
    createdAt: '2026-10-28T09:30:00Z',
    penaltyAccrued: 0,
    penaltyRepaid: 0,
    penaltyOutstanding: 0,
    ...overrides,
  };
}

export function disbursementFixture(overrides: Partial<Disbursement> = {}): Disbursement {
  return {
    id: 'd1000000-0000-4000-8000-000000000001',
    agreementId: AGREEMENT_ID,
    amount: 100000,
    currency: 'INR',
    status: 'SIMULATED_COMPLETED',
    reference: 'SIM-0011223344556677',
    disbursedAt: '2026-10-28T11:00:00Z',
    ...overrides,
  };
}

export function installmentFixture(overrides: Partial<Installment> = {}): Installment {
  return {
    sequenceNo: 1,
    dueDate: '2026-11-24',
    principalDue: 50000,
    feeDue: 1236.3,
    totalDue: 51236.3,
    principalPaid: 0,
    feePaid: 0,
    remaining: 51236.3,
    status: 'PENDING',
    penaltyAccrued: 0,
    penaltyPaid: 0,
    penaltyRemaining: 0,
    penaltyAccruedThrough: null,
    ...overrides,
  };
}

export function repaymentFixture(overrides: Partial<Repayment> = {}): Repayment {
  return {
    id: 'r0000000-0000-4000-8000-000000000001',
    agreementId: AGREEMENT_ID,
    amount: 10000,
    principalComponent: 8763.7,
    feeComponent: 1236.3,
    penaltyComponent: 0,
    currency: 'INR',
    receivedAt: '2026-10-29T05:00:00Z',
    ...overrides,
  };
}

export function viewFixture(overrides: Partial<AgreementView> = {}): AgreementView {
  return {
    agreement: agreementFixture(),
    disbursement: disbursementFixture(),
    schedule: [installmentFixture(), installmentFixture({ sequenceNo: 2, dueDate: '2026-12-19', feeDue: 1236.3 })],
    repayments: [],
    ...overrides,
  };
}
