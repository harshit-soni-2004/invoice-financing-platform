/** financing-service's OfferStatus: OFFERED is the only open state. */
export type OfferStatus = 'OFFERED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED' | 'CLOSED';
export const OFFER_STATUSES: readonly OfferStatus[] = ['OFFERED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CLOSED'];

export type AgreementStatus =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'DISBURSED'
  | 'REPAYMENT_IN_PROGRESS'
  | 'SETTLED';
export const AGREEMENT_STATUSES: readonly AgreementStatus[] = [
  'PENDING_APPROVAL',
  'APPROVED',
  'REJECTED',
  'CANCELLED',
  'DISBURSED',
  'REPAYMENT_IN_PROGRESS',
  'SETTLED',
];

/** AgreementStatus.acceptsRepayments(). */
export const ACCEPTS_REPAYMENTS: readonly AgreementStatus[] = ['DISBURSED', 'REPAYMENT_IN_PROGRESS'];
/** Statuses with a disbursement, a schedule and possibly repayments. */
export const DISBURSED_STATUSES: readonly AgreementStatus[] = ['DISBURSED', 'REPAYMENT_IN_PROGRESS', 'SETTLED'];

export type InstallmentStatus = 'PENDING' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE';
export const INSTALLMENT_STATUSES: readonly InstallmentStatus[] = ['PENDING', 'PARTIALLY_PAID', 'PAID', 'OVERDUE'];

export type Tone = 'neutral' | 'info' | 'primary' | 'success' | 'warning' | 'danger';

export interface Labelled {
  label: string;
  tone: Tone;
}

export const OFFER_STATUS_INFO: Readonly<Record<OfferStatus, Labelled>> = {
  OFFERED: { label: 'Open', tone: 'primary' },
  ACCEPTED: { label: 'Accepted', tone: 'success' },
  REJECTED: { label: 'Rejected', tone: 'neutral' },
  WITHDRAWN: { label: 'Withdrawn', tone: 'neutral' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
};

export const AGREEMENT_STATUS_INFO: Readonly<Record<AgreementStatus, Labelled & { next: string }>> = {
  PENDING_APPROVAL: {
    label: 'Awaiting approval',
    tone: 'info',
    next: 'The financier must approve this agreement before the advance is paid out.',
  },
  APPROVED: { label: 'Approved', tone: 'primary', next: 'The financier approved the agreement and will now pay out the advance.' },
  REJECTED: { label: 'Rejected', tone: 'danger', next: 'The financier rejected this agreement. No money was paid out.' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', next: 'This agreement was cancelled before any money was paid out.' },
  DISBURSED: { label: 'Disbursed', tone: 'success', next: 'The advance was paid out. Repay it by the installment due dates.' },
  REPAYMENT_IN_PROGRESS: {
    label: 'Repaying',
    tone: 'primary',
    next: 'Repayments have started. Keep paying installments by their due dates.',
  },
  SETTLED: { label: 'Settled', tone: 'success', next: 'Everything has been repaid. This agreement is settled.' },
};

export const INSTALLMENT_STATUS_INFO: Readonly<Record<InstallmentStatus, Labelled>> = {
  PENDING: { label: 'Due', tone: 'neutral' },
  PARTIALLY_PAID: { label: 'Partly paid', tone: 'info' },
  PAID: { label: 'Paid', tone: 'success' },
  OVERDUE: { label: 'Overdue', tone: 'danger' },
};

/** OfferResponse. Party IDs are returned too but are not shown to the seller. */
export interface Offer {
  id: string;
  invoiceId: string;
  advanceAmount: number;
  /** A fraction: 0.12 is 12% a year. */
  annualRate: number;
  processingFee: number;
  installmentCount: number;
  currency: string;
  expiresAt: string;
  status: OfferStatus;
  statusReason: string | null;
  decidedAt: string | null;
  createdAt: string;
}

/**
 * AgreementResponse. `overdue`, `overdueAmount` and `nextDueDate` are only filled by `GET /agreements/{id}`; fee and
 * totals are null until disbursement fixes them.
 */
export interface Agreement {
  id: string;
  offerId: string;
  invoiceId: string;
  invoiceAmount: number;
  advanceAmount: number;
  annualRate: number;
  processingFee: number;
  installmentCount: number;
  currency: string;
  maturityDate: string;
  status: AgreementStatus;
  statusReason: string | null;
  feeAmount: number | null;
  totalRepayable: number | null;
  principalRepaid: number;
  feeRepaid: number;
  outstanding: number;
  overdue: boolean | null;
  overdueAmount: number | null;
  nextDueDate: string | null;
  approvedAt: string | null;
  disbursedAt: string | null;
  settledAt: string | null;
  createdAt: string;
  penaltyAccrued: number;
  penaltyRepaid: number;
  penaltyOutstanding: number;
}

export interface Disbursement {
  id: string;
  agreementId: string;
  amount: number;
  currency: string;
  status: string;
  reference: string;
  disbursedAt: string;
}

/** InstallmentResponse: `remaining` includes unpaid penalty, so it is zero exactly when the status is PAID. */
export interface Installment {
  sequenceNo: number;
  dueDate: string;
  principalDue: number;
  feeDue: number;
  totalDue: number;
  principalPaid: number;
  feePaid: number;
  remaining: number;
  status: InstallmentStatus;
  penaltyAccrued: number;
  penaltyPaid: number;
  penaltyRemaining: number;
  penaltyAccruedThrough: string | null;
}

export interface Repayment {
  id: string;
  agreementId: string;
  amount: number;
  principalComponent: number;
  feeComponent: number;
  penaltyComponent: number;
  currency: string;
  receivedAt: string;
}

/** A recorded repayment; `replayed` means financing-service returned an earlier result for the same Idempotency-Key. */
export interface RepaymentResult {
  repayment: Repayment;
  replayed: boolean;
}

/** Everything the agreement panel shows. The disbursement, schedule and repayments exist only once disbursed. */
export interface AgreementView {
  agreement: Agreement;
  disbursement: Disbursement | null;
  schedule: Installment[];
  repayments: Repayment[];
}

/** financing-service's ReasonRequest limit. */
export const REASON_MAX = 500;
