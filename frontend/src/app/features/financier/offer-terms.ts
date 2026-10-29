import { FINANCING_LIMITS } from './financier.models';

const DAY_MS = 24 * 60 * 60 * 1000;

/** At most 17 integer digits and 2 decimals, like financing-service's `@Digits(integer = 17, fraction = 2)`. */
export const AMOUNT = /^\d{1,17}(\.\d{1,2})?$/;
/** A percentage with at most 2 decimals, so the annual fraction has at most 4 (`OfferTerms` rejects more). */
const PERCENT = /^\d{1,3}(\.\d{1,2})?$/;
const LOCAL_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;

/** People type "4,80,000.50"; the API wants "480000.50". */
export function normalizeAmount(value: string): string {
  return value.replace(/[\s,]/g, '');
}

/** `"12.5"` (percent per year) -> `"0.1250"` (the fraction financing-service expects), or null if not a valid percent. */
export function percentToFraction(percent: string): string | null {
  const value = percent.trim().replace(/%$/, '').trim();
  if (!PERCENT.test(value)) {
    return null;
  }
  const [whole, decimals = ''] = value.split('.');
  const basisPoints = Number(whole) * 100 + Number(decimals.padEnd(2, '0'));
  return `${Math.floor(basisPoints / 10000)}.${String(basisPoints % 10000).padStart(4, '0')}`;
}

/** The instant an invoice's due date starts in financing-service's business zone (Asia/Kolkata). */
export function dueDateStart(dueDate: string): number {
  return Date.parse(`${dueDate}T00:00:00${FINANCING_LIMITS.businessZoneOffset}`);
}

/** A `datetime-local` value is wall-clock time in the browser's zone; null if it is not one. */
export function localDateTimeToInstant(value: string): Date | null {
  if (!LOCAL_DATE_TIME.test(value)) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

export function toLocalDateTime(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export type ExpiryProblem = 'past' | 'too-far' | 'after-due';

/** The same three checks as financing-service: in the future, within the validity window, before the due date starts. */
export function expiryProblem(expiresAt: Date, dueDate: string, now: number): ExpiryProblem | null {
  const at = expiresAt.getTime();
  if (at <= now) {
    return 'past';
  }
  if (at > now + FINANCING_LIMITS.maxOfferValidityDays * DAY_MS) {
    return 'too-far';
  }
  return at < dueDateStart(dueDate) ? null : 'after-due';
}

/** A sensible starting expiry: a week from now, or an hour before the due date starts if that is sooner. */
export function defaultExpiry(dueDate: string, now: number): string {
  const week = now + 7 * DAY_MS;
  const beforeDue = dueDateStart(dueDate) - 60 * 60 * 1000;
  const candidate = Math.min(week, beforeDue);
  if (candidate <= now + 60 * 60 * 1000) {
    return '';
  }
  const date = new Date(candidate);
  date.setSeconds(0, 0);
  return toLocalDateTime(date);
}

/**
 * The fee if the advance were disbursed today, as financing-service computes it at disbursement:
 * advance x rate x days to the due date / 365, plus the processing fee. Shown as an estimate only, because the real
 * fee depends on the disbursement day.
 */
export function estimatedFee(advance: number, annualRate: number, daysToMaturity: number, processingFee: number): number {
  const interest = Math.round(((advance * annualRate * Math.max(daysToMaturity, 0)) / 365) * 100) / 100;
  return Math.round((interest + processingFee) * 100) / 100;
}
