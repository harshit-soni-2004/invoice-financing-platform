import { canonicalAmount, checkRepaymentAmount, financingFailureMessage, formatRate } from './financing-format';

describe('financing format', () => {
  it('shows fractional rates as percentages', () => {
    expect(formatRate(0.12)).toBe('12%');
    expect(formatRate(0.1275)).toBe('12.75%');
  });

  describe('checkRepaymentAmount mirrors RepaymentRequest and AgreementService', () => {
    const outstanding = 1500.5;

    for (const [input, expected] of [
      ['', { error: 'Enter the amount you repaid.' }],
      ['abc', { error: 'Enter an amount in rupees with at most 2 decimal places, like 25000.50.' }],
      ['10.123', { error: 'Enter an amount in rupees with at most 2 decimal places, like 25000.50.' }],
      ['-5', { error: 'Enter an amount in rupees with at most 2 decimal places, like 25000.50.' }],
      ['123456789012345678', { error: 'Enter an amount in rupees with at most 2 decimal places, like 25000.50.' }],
      ['0', { error: 'The amount must be at least ₹0.01.' }],
      ['0.00', { error: 'The amount must be at least ₹0.01.' }],
      ['1500.51', { error: 'The amount is more than what is outstanding.' }],
      ['1,500.50', { amount: '1500.50' }],
      ['0.01', { amount: '0.01' }],
      ['100', { amount: '100.00' }],
      ['0100.5', { amount: '100.50' }],
    ] as const) {
      it(`"${input}"`, () => {
        expect(checkRepaymentAmount(input, outstanding)).toEqual(expected);
      });
    }

    it('accepts exactly the outstanding amount despite floating-point sums', () => {
      expect(checkRepaymentAmount('0.30', 0.1 + 0.2)).toEqual({ amount: '0.30' });
    });
  });

  it('canonicalizes amounts on the string so large values keep every digit', () => {
    expect(canonicalAmount('12345678901234567.8')).toBe('12345678901234567.80');
    expect(canonicalAmount('0.5')).toBe('0.50');
  });

  it('words conflicts by code and never shows server text except 422 rules', () => {
    expect(financingFailureMessage({ kind: 'conflict', code: 'OFFER_EXPIRED', currentStatus: null })).toContain('expired');
    expect(financingFailureMessage({ kind: 'conflict', code: 'INVALID_STATUS_TRANSITION', currentStatus: 'ACCEPTED' })).toBe(
      'This is now accepted, so that action is no longer possible.',
    );
    expect(financingFailureMessage({ kind: 'conflict', code: 'INVALID_STATUS_TRANSITION', currentStatus: 'SETTLED' })).toBe(
      'This is now settled, so that action is no longer possible.',
    );
    expect(financingFailureMessage({ kind: 'conflict', code: 'IDEMPOTENCY_KEY_REUSED', currentStatus: null })).toContain(
      'different amount',
    );
    expect(financingFailureMessage({ kind: 'rule', code: 'X', message: 'Server says no' })).toBe('Server says no');
    expect(financingFailureMessage({ kind: 'rule', code: 'X', message: null })).toBe('The request does not meet the platform rules.');
    expect(financingFailureMessage({ kind: 'unavailable' })).toContain('financing service');
    expect(financingFailureMessage({ kind: 'rate-limited', retryAfterSeconds: 9 })).toContain('9 seconds');
  });
});
