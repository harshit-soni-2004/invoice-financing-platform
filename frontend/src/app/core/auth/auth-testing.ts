import { base64UrlEncode } from './pkce';

/** An unsigned JWT with the given claims, enough for code that reads claims from a token received over TLS. */
export function fakeJwt(claims: Record<string, unknown>): string {
  const part = (value: unknown) => base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
  return `${part({ alg: 'RS256', kid: 'test' })}.${part(claims)}.c2lnbmF0dXJl`;
}

export const TEST_CONFIG = {
  apiBaseUrl: 'http://gateway.test',
  authIssuer: 'http://auth.test',
  clientId: 'ifp-spa',
};

export const TEST_DISCOVERY = {
  issuer: 'http://auth.test',
  authorization_endpoint: 'http://auth.test/oauth2/authorize',
  token_endpoint: 'http://auth.test/oauth2/token',
  end_session_endpoint: 'http://auth.test/connect/logout',
};
