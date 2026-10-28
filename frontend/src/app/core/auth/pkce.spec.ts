import { base64UrlDecode, base64UrlEncode, randomUrlSafe, s256Challenge } from './pkce';

describe('PKCE helpers', () => {
  it('derives the S256 challenge from RFC 7636 Appendix B', async () => {
    expect(await s256Challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('creates 43-character URL-safe verifiers that differ every time', () => {
    const values = new Set(Array.from({ length: 20 }, () => randomUrlSafe()));
    expect(values.size).toBe(20);
    for (const value of values) {
      expect(value).toMatch(/^[A-Za-z0-9_-]{43}$/);
    }
  });

  it('round-trips base64url without padding', () => {
    const bytes = Uint8Array.from([0, 250, 251, 252, 253, 254, 255]);
    const encoded = base64UrlEncode(bytes);
    expect(encoded).not.toMatch(/[+/=]/);
    expect(Array.from(base64UrlDecode(encoded))).toEqual(Array.from(bytes));
  });
});
