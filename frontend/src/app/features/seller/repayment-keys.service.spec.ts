import { RepaymentKeysService } from './repayment-keys.service';

describe('RepaymentKeysService', () => {
  it('keeps one key per agreement and amount until the repayment is confirmed', () => {
    const keys = new RepaymentKeysService();
    const first = keys.keyFor('agreement-1', '100.00');

    expect(first).toMatch(/^[A-Za-z0-9_-]{8,100}$/);
    expect(keys.keyFor('agreement-1', '100.00')).toBe(first);
    expect(keys.keyFor('agreement-2', '100.00')).not.toBe(first);

    keys.forget('agreement-1');
    expect(keys.keyFor('agreement-1', '100.00')).not.toBe(first);
  });

  it('uses a new key when the amount changes, since financing-service refuses a reused key for another amount', () => {
    const keys = new RepaymentKeysService();
    const first = keys.keyFor('agreement-1', '100.00');
    const second = keys.keyFor('agreement-1', '200.00');
    expect(second).not.toBe(first);
    expect(keys.keyFor('agreement-1', '200.00')).toBe(second);
  });
});
