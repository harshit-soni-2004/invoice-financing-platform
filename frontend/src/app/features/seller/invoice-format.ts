import { InvoiceFailure } from './invoice-api.service';
import { STATUS_INFO } from './invoice.models';

const LOCALE = 'en-IN';

const dateFormat = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeZone: 'UTC' });
const instantFormat = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' });

export function formatAmount(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat(LOCALE, { style: 'currency', currency }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}

/** Calendar dates (`2026-10-29`) carry no time zone, so they are shown as-is rather than shifted to local time. */
export function formatDate(date: string): string {
  const parsed = new Date(`${date}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) ? date : dateFormat.format(parsed);
}

export function formatInstant(instant: string): string {
  const parsed = new Date(instant);
  return Number.isNaN(parsed.getTime()) ? instant : instantFormat.format(parsed);
}

/** A message for any failed invoice request. Nothing from the server is shown except invoice-service's rule text. */
export function failureMessage(failure: InvoiceFailure): string {
  switch (failure.kind) {
    case 'invalid':
      return 'Some details were not accepted. Check the highlighted fields.';
    case 'rule':
      return failure.message ?? 'The invoice does not meet the platform rules. Check the amount and dates.';
    case 'duplicate-number':
      return 'You already have an invoice with this number. Use a different invoice number.';
    case 'invalid-transition':
      return failure.currentStatus
        ? `This invoice is now ${STATUS_INFO[failure.currentStatus].label.toLowerCase()}, so that action is no longer possible.`
        : 'That action is not possible for this invoice in its current status.';
    case 'not-editable':
      return 'This invoice can no longer be changed.';
    case 'concurrent':
      return 'This invoice was changed by another request at the same moment. Its latest details are shown; check them and try again.';
    case 'stale':
      return 'This invoice changed since you opened it. Its latest details are shown; check them and try again.';
    case 'unauthenticated':
      return 'Your session has ended. Sign in again to continue.';
    case 'forbidden':
      return 'You do not have permission to do this.';
    case 'not-found':
      return 'This invoice does not exist, or it belongs to another account.';
    case 'rate-limited':
      return failure.retryAfterSeconds !== null && failure.retryAfterSeconds > 1
        ? `Too many requests. Wait ${failure.retryAfterSeconds} seconds and try again.`
        : 'Too many requests. Wait a moment and try again.';
    case 'network':
      return 'The server could not be reached. Check your connection and try again.';
    case 'unavailable':
      return 'The invoice service is temporarily unavailable. Try again in a few minutes.';
    case 'unexpected':
      return 'Something went wrong. Try again in a moment.';
  }
}
