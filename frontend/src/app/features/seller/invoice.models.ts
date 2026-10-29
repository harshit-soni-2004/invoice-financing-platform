/** invoice-service's lifecycle, including the two terminal off-ramps. */
export type InvoiceStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'BUYER_CONFIRMED'
  | 'VERIFIED'
  | 'FINANCING_AVAILABLE'
  | 'FINANCED'
  | 'SETTLED'
  | 'REJECTED'
  | 'CANCELLED';

export const INVOICE_STATUSES: readonly InvoiceStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'BUYER_CONFIRMED',
  'VERIFIED',
  'FINANCING_AVAILABLE',
  'FINANCED',
  'SETTLED',
  'REJECTED',
  'CANCELLED',
];

type BadgeTone = 'neutral' | 'info' | 'primary' | 'success' | 'danger';

interface StatusInfo {
  label: string;
  tone: BadgeTone;
  /** What happens next, from the seller's point of view. */
  next: string;
}

export const STATUS_INFO: Readonly<Record<InvoiceStatus, StatusInfo>> = {
  DRAFT: {
    label: 'Draft',
    tone: 'neutral',
    next: 'Only you can see this invoice. Submit it when the details are final; an administrator then checks it with the buyer.',
  },
  SUBMITTED: {
    label: 'Submitted',
    tone: 'info',
    next: 'An administrator is confirming this invoice with the buyer.',
  },
  BUYER_CONFIRMED: {
    label: 'Buyer confirmed',
    tone: 'info',
    next: 'The buyer confirmed this invoice. An administrator is completing the verification.',
  },
  VERIFIED: {
    label: 'Verified',
    tone: 'primary',
    next: 'This invoice is verified. Make it available to financiers when you want offers.',
  },
  FINANCING_AVAILABLE: {
    label: 'Open for financing',
    tone: 'primary',
    next: 'Financiers can now see this invoice and make offers.',
  },
  FINANCED: { label: 'Financed', tone: 'success', next: 'This invoice has been financed.' },
  SETTLED: { label: 'Settled', tone: 'success', next: 'This invoice has been repaid and settled.' },
  REJECTED: { label: 'Rejected', tone: 'danger', next: 'This invoice was rejected and cannot continue.' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', next: 'This invoice was cancelled and cannot continue.' },
};

/** The seller-driven transitions invoice-service offers, keyed by the status they start from. */
export type SellerAction = 'submit' | 'list-for-financing';

export const SELLER_ACTION_FROM: Readonly<Record<SellerAction, InvoiceStatus>> = {
  submit: 'DRAFT',
  'list-for-financing': 'VERIFIED',
};

/** The administrator's verification steps, keyed by the statuses each one may start from. */
export type ReviewAction = 'buyer-confirmation' | 'verify' | 'reject';

export const REVIEW_ACTION_FROM: Readonly<Record<ReviewAction, readonly InvoiceStatus[]>> = {
  'buyer-confirmation': ['SUBMITTED'],
  verify: ['BUYER_CONFIRMED'],
  reject: ['SUBMITTED', 'BUYER_CONFIRMED'],
};

/** invoice-service's limit on a rejection reason. */
export const REJECT_REASON_MAX = 500;

/** One row of `GET /api/invoices`. */
export interface InvoiceSummary {
  id: string;
  invoiceNumber: string;
  buyerName: string;
  amount: number;
  currency: string;
  dueDate: string;
  status: InvoiceStatus;
  createdAt: string;
}

/** `GET /api/invoices/{id}` and the result of every write. */
export interface Invoice {
  id: string;
  invoiceNumber: string;
  buyerName: string;
  buyerGstin: string | null;
  description: string | null;
  amount: number;
  currency: string;
  issueDate: string;
  dueDate: string;
  status: InvoiceStatus;
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

/** An invoice together with the strong ETag that writes must send back as `If-Match`. */
export interface VersionedInvoice {
  invoice: Invoice;
  etag: string;
}

export interface InvoicePage {
  content: InvoiceSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * Body of `POST /api/invoices`. There is deliberately no seller field: invoice-service takes the seller from the
 * access token. The amount is a decimal string so no precision is lost on the way.
 */
export interface InvoiceDraft {
  invoiceNumber: string;
  buyerName: string;
  buyerGstin: string | null;
  description: string | null;
  amount: string;
  issueDate: string;
  dueDate: string;
}

export type InvoiceField = keyof InvoiceDraft;
