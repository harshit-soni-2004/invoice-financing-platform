import { passwordViolation } from './password-policy';

describe('passwordViolation', () => {
  const email = 'asha@example.test';

  it('accepts a 12-character passphrase', () => {
    expect(passwordViolation('correct-hors', email)).toBeNull();
  });

  it('requires a non-blank password', () => {
    expect(passwordViolation('', email)).toBe('is required');
    expect(passwordViolation(' '.repeat(14), email)).toBe('is required');
  });

  it('counts characters, not UTF-16 units, for the minimum length', () => {
    expect(passwordViolation('short-pass1', email)).toMatch(/at least 12/);
    // Six emoji are 12 UTF-16 units but only 6 characters.
    expect(passwordViolation('😀😀😀😀😀😀', email)).toMatch(/at least 12/);
  });

  it('limits the password to 72 UTF-8 bytes', () => {
    expect(passwordViolation('a'.repeat(72), email)).toBeNull();
    expect(passwordViolation('a'.repeat(73), email)).toMatch(/too long/);
    // 25 two-byte characters are 50 bytes plus 23 ASCII characters: 73 bytes in 48 characters.
    expect(passwordViolation('é'.repeat(25) + 'a'.repeat(23), email)).toMatch(/too long/);
  });

  it('rejects the email address as the password, ignoring case and surrounding spaces', () => {
    expect(passwordViolation(' ASHA@example.test ', email)).toMatch(/email/);
    expect(passwordViolation('longer-email@x.test', '')).toBeNull();
  });
});
