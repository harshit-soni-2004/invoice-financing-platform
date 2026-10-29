import { failureMessage, formatAmount, formatDate } from './invoice-format';

describe('invoice formatting', () => {
  it('formats rupees with Indian digit grouping', () => {
    expect(formatAmount(125000.5, 'INR')).toBe('₹1,25,000.50');
  });

  it('shows calendar dates without shifting them by time zone', () => {
    expect(formatDate('2026-12-19')).toBe('19 Dec 2026');
    expect(formatDate('not a date')).toBe('not a date');
  });

  it('never shows raw server text other than business-rule messages', () => {
    expect(failureMessage({ kind: 'unexpected' })).toBe('Something went wrong. Try again in a moment.');
    expect(failureMessage({ kind: 'rule', field: null, message: null })).toContain('platform rules');
    expect(failureMessage({ kind: 'rate-limited', retryAfterSeconds: 20 })).toContain('Wait 20 seconds');
  });
});
