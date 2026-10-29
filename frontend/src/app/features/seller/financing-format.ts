import { commonFailureMessage } from '../../shared/api-failure';
import { FinancingFailure } from './financing-api.service';
import { AGREEMENT_STATUS_INFO, AgreementStatus, OFFER_STATUS_INFO, OfferStatus, Tone } from './financing.models';
import { AMOUNT, normalizeAmount } from './invoice-validators';

const percentFormat = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 2 });

/** financing-service rates are fractions: 0.125 is shown as "12.5%". */
export function formatRate(rate: number): string {
  return percentFormat.format(rate);
}

export function badgeClass(tone: Tone): string {
  return tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
}

function statusLabel(status: string | null): string | null {
  if (status === null) {
    return null;
  }
  const info = OFFER_STATUS_INFO[status as OfferStatus] ?? AGREEMENT_STATUS_INFO[status as AgreementStatus];
  return info ? info.label.toLowerCase() : null;
}

/** A message for any failed financing request. Only financing-service's 422 rule text is shown from the server. */
export function financingFailureMessage(failure: FinancingFailure): string {
  switch (failure.kind) {
    case 'rule':
      return failure.message ?? 'The request does not meet the platform rules.';
    case 'conflict':
      switch (failure.code) {
        case 'OFFER_EXPIRED':
          return 'This offer has expired, so it can no longer be accepted or rejected.';
        case 'INVALID_STATUS_TRANSITION': {
          const label = statusLabel(failure.currentStatus);
          return label ? `This is now ${label}, so that action is no longer possible.` : 'That action is no longer possible.';
        }
        case 'INVOICE_ALREADY_FINANCED':
          return 'This invoice already has a financing agreement, so no other offer can be accepted.';
        case 'AGREEMENT_SETTLED':
          return 'This agreement is already fully repaid.';
        case 'REPAYMENT_NOT_ALLOWED':
          return 'Repayments are not accepted for this agreement in its current status.';
        case 'IDEMPOTENCY_KEY_REUSED':
          return 'This repayment attempt was already used for a different amount. Check the repayment history before trying again.';
        default:
          return 'This was changed by another request at the same moment. The latest details are shown; check them and try again.';
      }
    case 'not-found':
      return 'This no longer exists, or it belongs to another account.';
    default:
      return commonFailureMessage(failure, 'financing service');
  }
}

/**
 * Checks a repayment amount the way RepaymentRequest and AgreementService do: required, positive, at most 17 integer
 * digits and 2 decimals, and no more than what is outstanding. Returns the normalized amount or an error message.
 */
export function checkRepaymentAmount(raw: string, outstanding: number): { amount: string } | { error: string } {
  const value = normalizeAmount(raw);
  if (value === '') {
    return { error: 'Enter the amount you repaid.' };
  }
  if (!AMOUNT.test(value)) {
    return { error: 'Enter an amount in rupees with at most 2 decimal places, like 25000.50.' };
  }
  const amount = Number(value);
  if (amount < 0.01) {
    return { error: 'The amount must be at least ₹0.01.' };
  }
  // Compare in paise so 0.1 + 0.2 style float error cannot reject the exact outstanding amount.
  if (Math.round(amount * 100) > Math.round(outstanding * 100)) {
    return { error: 'The amount is more than what is outstanding.' };
  }
  return { amount: canonicalAmount(value) };
}

/**
 * "0100.5" -> "100.50". financing-service hashes the amount at scale 2, so "100" and "100.00" are the same repayment;
 * the canonical form makes them share one Idempotency-Key too. Done on the string, so no precision is lost.
 */
export function canonicalAmount(value: string): string {
  const [whole, fraction = ''] = value.split('.');
  return `${whole.replace(/^0+(?=\d)/, '')}.${fraction.padEnd(2, '0')}`;
}
