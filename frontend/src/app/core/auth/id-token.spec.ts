import { fakeJwt } from './auth-testing';
import { IdTokenError, readIdToken } from './id-token';

describe('readIdToken', () => {
  const now = 1_800_000_000;
  const expected = { issuer: 'http://auth.test', clientId: 'ifp-spa', nonce: 'n-1', nowSeconds: now };
  const valid = {
    iss: 'http://auth.test',
    aud: ['ifp-spa'],
    sub: '0b8f7a52-3c1e-4c7a-9d55-2f1f0d6c9a10',
    nonce: 'n-1',
    exp: now + 1800,
    roles: ['SELLER'],
    name: 'Asha Rao',
  };

  it('returns the user for a valid token', () => {
    expect(readIdToken(fakeJwt(valid), expected)).toEqual({
      id: valid.sub,
      name: 'Asha Rao',
      role: 'SELLER',
    });
  });

  it('accepts a single string audience', () => {
    expect(readIdToken(fakeJwt({ ...valid, aud: 'ifp-spa' }), expected).role).toBe('SELLER');
  });

  const rejected: [string, Record<string, unknown>][] = [
    ['another issuer', { iss: 'http://evil.test' }],
    ['another audience', { aud: ['other-app'] }],
    ['several audiences without azp', { aud: ['ifp-spa', 'other-app'] }],
    ['a different nonce', { nonce: 'n-2' }],
    ['a missing nonce', { nonce: undefined }],
    ['an expired token', { exp: now - 61 }],
    ['a missing subject', { sub: '' }],
    ['no known role', { roles: ['SUPERUSER'] }],
    ['roles that are not a list', { roles: 'ADMIN' }],
  ];
  for (const [label, override] of rejected) {
    it(`rejects ${label}`, () => {
      expect(() => readIdToken(fakeJwt({ ...valid, ...override }), expected)).toThrowError(IdTokenError);
    });
  }

  it('rejects tokens that are not JWTs', () => {
    expect(() => readIdToken('not-a-token', expected)).toThrowError(IdTokenError);
    expect(() => readIdToken('a.bm90LWpzb24.c', expected)).toThrowError(IdTokenError);
  });

  it('falls back to a neutral name when none is present', () => {
    expect(readIdToken(fakeJwt({ ...valid, name: undefined }), expected).name).toBe('Signed-in user');
  });
});
