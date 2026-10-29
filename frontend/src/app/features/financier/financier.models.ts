/** financing-service's offer lifecycle. OFFERED is the only open state. */
export type OfferStatus = 'OFFERED' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED' | 'CLOSED';

export const OFFER_STATUSES: readonly OfferStatus[] = ['OFFERED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CLOSED'];

/** financing-service's agreement lifecycle. "Overdue" is derived from the schedule, not a status. */
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
  'DISBURSED',
  'REPAYMENT_IN_PROGRESS',
  'SETTLED',
  'REJECTED',
  'CANCELLED',
];

export type InstallmentStatus = 'PENDING' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE';

export const INSTALLMENT_STATUSES: readonly InstallmentStatus[] = ['PENDING', 'PARTIALLY_PAID', 'PAID', 'OVERDUE'];

export type BadgeTone = 'neutral' | 'info' | 'primary' | 'success' | 'warning' | 'danger';

export interface BadgeInfo {
  label: string;
  tone: BadgeTone;
}

export const OFFER_STATUS_INFO: Readonly<Record<OfferStatus, BadgeInfo & { next: string }>> = {
  OFFERED: { label: 'Open', tone: 'primary', next: 'The seller can accept or reject this offer until it expires.' },
  ACCEPTED: { label: 'Accepted', tone: 'success', next: 'The seller accepted this offer. Approve the agreement it created.' },
  REJECTED: { label: 'Rejected', tone: 'danger', next: 'The seller rejected this offer.' },
  WITHDRAWN: { label: 'Withdrawn', tone: 'neutral', next: 'You withdrew this offer.' },
  EXPIRED: { label: 'Expired', tone: 'neutral', next: 'This offer expired before the seller decided.' },
  CLOSED: { label: 'Closed', tone: 'neutral', next: 'This offer closed, for example because the seller accepted another offer.' },
};

export const AGREEMENT_STATUS_INFO: Readonly<Record<AgreementStatus, BadgeInfo & { next: string }>> = {
  PENDING_APPROVAL: {
    label: 'Awaiting your approval',
    tone: 'warning',
    next: 'The seller accepted your offer. Approve the agreement to continue to disbursement.',
  },
  APPROVED: {
    label: 'Approved',
    tone: 'primary',
    next: 'You approved this agreement. Disburse the advance to start the repayment schedule.',
  },
  DISBURSED: { label: 'Disbursed', tone: 'info', next: 'The advance was disbursed. The seller repays against the schedule.' },
  REPAYMENT_IN_PROGRESS: {
    label: 'Repaying',
    tone: 'info',
    next: 'The seller has started repaying. The agreement settles when nothing is outstanding.',
  },
  SETTLED: { label: 'Settled', tone: 'success', next: 'Everything has been repaid.' },
  REJECTED: { label: 'Rejected', tone: 'danger', next: 'This agreement was rejected and cannot continue.' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', next: 'This agreement was cancelled and cannot continue.' },
};

export const INSTALLMENT_STATUS_INFO: Readonly<Record<InstallmentStatus, BadgeInfo>> = {
  PENDING: { label: 'Not yet due', tone: 'neutral' },
  PARTIALLY_PAID: { label: 'Partly paid', tone: 'info' },
  PAID: { label: 'Paid', tone: 'success' },
  OVERDUE: { label: 'Overdue', tone: 'danger' },
};

/** The financier-driven agreement steps this milestone offers, keyed by the status they start from. */
export type FinancierAction = 'approve' | 'disburse';

export const FINANCIER_ACTION_FROM: Readonly<Record<FinancierAction, AgreementStatus>> = {
  approve: 'PENDING_APPROVAL',
  disburse: 'APPROVED',
};

/** Fields the opportunity list may be sorted by, as financing-service whitelists them. */
export type OpportunitySortField = 'dueDate' | 'amount' | 'invoiceNumber';
export type SortDirection = 'asc' | 'desc';

export interface OpportunitySort {
  field: OpportunitySortField;
  direction: SortDirection;
}

/**
 * Bounds from financing-service's `application.yml` (`financing.*`). They are configurable on the server and not
 * published by the API, so the form uses them for early feedback only; the server's 422 answer is authoritative.
 * The advance cap is not here: every opportunity carries its own `maxAdvanceAmount`.
 */
export const FINANCING_LIMITS = {
  maxAnnualRatePercent: 36,
  maxInstallments: 12,
  maxOfferValidityDays: 14,
  businessZoneOffset: '+05:30',
} as const;

/** One row of `GET /api/financing/opportunities`, and the body of `GET /api/financing/opportunities/{invoiceId}`. */
export interface Opportunity {
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

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Offer {
  id: string;
  invoiceId: string;
  advanceAmount: number;
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
 * Body of `POST /api/financing/offers`. Amounts and the rate are decimal strings so no precision is lost; the rate is
 * an annual fraction (`0.1250` = 12.5% p.a.). There is no financier field: financing-service takes it from the token.
 */
export interface OfferDraft {
  invoiceId: string;
  advanceAmount: string;
  annualRate: string;
  processingFee: string;
  installmentCount: number;
  expiresAt: string;
}

export type OfferField = Exclude<keyof OfferDraft, 'invoiceId'>;

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
  /** Fixed at disbursement; null before. */
  feeAmount: number | null;
  totalRepayable: number | null;
  principalRepaid: number;
  feeRepaid: number;
  /** Zero until disbursement fixes the total repayable. */
  outstanding: number;
  /** Only in the single-agreement response: the list omits overdue figures. */
  overdue: boolean | null;
  overdueAmount: number | null;
  nextDueDate: string | null;
  approvedAt: string | null;
  disbursedAt: string | null;
  settledAt: string | null;
  createdAt: string;
}

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

/** The disbursement plus whether financing-service answered from its idempotency record (`Idempotent-Replayed`). */
export interface DisbursementResult {
  disbursement: Disbursement;
  replayed: boolean;
}
