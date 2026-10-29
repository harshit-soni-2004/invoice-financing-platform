import { FinancingFailure } from './financing-api.service';
import { AGREEMENT_STATUS_INFO, AgreementStatus, OFFER_STATUS_INFO, OfferStatus } from './financier.models';

export { formatAmount, formatDate, formatInstant } from '../seller/invoice-format';

/** `0.1250` -> `12.5% p.a.` */
export function formatRate(annualRate: number): string {
  return `${Number((annualRate * 100).toFixed(2))}% p.a.`;
}

function statusLabel(status: string | null): string | null {
  if (status && status in AGREEMENT_STATUS_INFO) {
    return AGREEMENT_STATUS_INFO[status as AgreementStatus].label.toLowerCase();
  }
  if (status && status in OFFER_STATUS_INFO) {
    return OFFER_STATUS_INFO[status as OfferStatus].label.toLowerCase();
  }
  return null;
}

/** A message for any failed financing request. Nothing from the server is shown except financing-service's rule text. */
export function failureMessage(failure: FinancingFailure): string {
  switch (failure.kind) {
    case 'invalid':
      return 'Some details were not accepted. Check the highlighted fields.';
    case 'rule':
      return failure.message ?? 'The request does not meet the financing rules. Check the details and try again.';
    case 'duplicate-offer':
      return 'You already have an open offer on this invoice. Withdraw it first if you want to offer different terms.';
    case 'already-financed':
      return 'This invoice already has a financing agreement, so it no longer accepts offers.';
    case 'offer-expired':
      return 'This offer has expired.';
    case 'invalid-transition': {
      const label = statusLabel(failure.currentStatus);
      return label
        ? `This is now ${label}, so that action is no longer possible. The latest details are shown.`
        : 'That action is not possible in the current status. The latest details are shown.';
    }
    case 'concurrent':
      return 'This was changed by another request at the same moment. The latest details are shown; check them and try again.';
    case 'idempotency-reused':
      return 'This request was already used for a different operation. Reload the page and try again.';
    case 'conflict':
      return 'The change conflicts with the current state. The latest details are shown.';
    case 'stale':
      return 'This changed since you opened it. The latest details are shown; check them and try again.';
    case 'unauthenticated':
      return 'Your session has ended. Sign in again to continue.';
    case 'forbidden':
      return 'Your account cannot use the financier tools. New financier accounts need an administrator’s approval first.';
    case 'not-found':
      return 'This does not exist, or it is not available to your account.';
    case 'rate-limited':
      return failure.retryAfterSeconds !== null && failure.retryAfterSeconds > 1
        ? `Too many requests. Wait ${failure.retryAfterSeconds} seconds and try again.`
        : 'Too many requests. Wait a moment and try again.';
    case 'network':
      return 'The server could not be reached. Check your connection and try again.';
    case 'unavailable':
      return 'The financing service is temporarily unavailable. Try again in a few minutes.';
    case 'unexpected':
      return 'Something went wrong. Try again in a moment.';
  }
}
