import {
  defaultExpiry,
  dueDateStart,
  estimatedFee,
  expiryProblem,
  localDateTimeToInstant,
  normalizeAmount,
  percentToFraction,
} from './offer-terms';

const DAY = 86_400_000;

describe('offer terms', () => {
  it('normalizes typed amounts', () => {
    expect(normalizeAmount(' 4,80,000.50 ')).toBe('480000.50');
  });

  it('turns a yearly percentage into the 4-decimal fraction financing-service expects', () => {
    expect(percentToFraction('12.5')).toBe('0.1250');
    expect(percentToFraction('12')).toBe('0.1200');
    expect(percentToFraction('0.01')).toBe('0.0001');
    expect(percentToFraction('36%')).toBe('0.3600');
    expect(percentToFraction('100')).toBe('1.0000');
    expect(percentToFraction('12.345')).toBeNull();
    expect(percentToFraction('abc')).toBeNull();
    expect(percentToFraction('')).toBeNull();
  });

  it('starts the due date at midnight India time', () => {
    expect(new Date(dueDateStart('2026-12-19')).toISOString()).toBe('2026-12-18T18:30:00.000Z');
  });

  it('applies financing-service’s three expiry checks', () => {
    const now = Date.parse('2026-10-29T06:00:00Z');
    const due = '2026-12-19';
    expect(expiryProblem(new Date(now - 1), due, now)).toBe('past');
    expect(expiryProblem(new Date(now), due, now)).toBe('past');
    expect(expiryProblem(new Date(now + 14 * DAY + 1000), due, now)).toBe('too-far');
    expect(expiryProblem(new Date(now + 14 * DAY), due, now)).toBeNull();
    expect(expiryProblem(new Date(now + 3 * DAY), '2026-11-01', now)).toBe('after-due');
    expect(expiryProblem(new Date(Date.parse('2026-10-31T18:29:00Z')), '2026-11-01', now)).toBeNull();
  });

  it('suggests an expiry a week out, or before the due date when that is sooner', () => {
    const now = new Date(2026, 9, 29, 10, 15).getTime();
    expect(defaultExpiry('2026-12-19', now)).toBe('2026-11-05T10:15');
    const soon = localDateTimeToInstant(defaultExpiry('2026-11-01', now))!;
    expect(soon.getTime()).toBeLessThan(dueDateStart('2026-11-01'));
    expect(defaultExpiry('2026-10-29', now)).toBe('');
  });

  it('reads datetime-local values only', () => {
    expect(localDateTimeToInstant('2026-11-05T10:15')).toEqual(new Date(2026, 10, 5, 10, 15));
    expect(localDateTimeToInstant('2026-11-05')).toBeNull();
  });

  it('estimates the fee the way financing-service fixes it at disbursement', () => {
    // The worked example in docs/architecture.md: ₹4,80,000 at 12% for 60 days plus ₹1,000.
    expect(estimatedFee(480000, 0.12, 60, 1000)).toBe(10468.49);
    expect(estimatedFee(480000, 0.12, -3, 0)).toBe(0);
  });
});
