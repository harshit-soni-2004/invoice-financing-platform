import { base64UrlDecode } from './pkce';

export type Role = 'SELLER' | 'FINANCIER' | 'ADMIN';

export const ROLES: readonly Role[] = ['SELLER', 'FINANCIER', 'ADMIN'];

export interface SessionUser {
  id: string;
  name: string;
  role: Role;
}

export interface IdTokenExpectations {
  issuer: string;
  clientId: string;
  nonce: string;
  nowSeconds: number;
}

export class IdTokenError extends Error {}

/** Allowed clock difference between this browser and auth-service when checking `exp`. */
const CLOCK_SKEW_SECONDS = 60;

/**
 * Reads the user from an ID token received directly from the token endpoint over TLS. OpenID Connect Core 3.1.3.7
 * allows relying on TLS instead of the signature in that case. The APIs always verify the access token's signature.
 */
export function readIdToken(idToken: string, expected: IdTokenExpectations): SessionUser {
  const claims = decodeClaims(idToken);

  if (claims['iss'] !== expected.issuer) {
    throw new IdTokenError('The sign-in response came from an unexpected issuer.');
  }
  const aud = claims['aud'];
  const audiences = Array.isArray(aud) ? aud : [aud];
  if (!audiences.includes(expected.clientId)) {
    throw new IdTokenError('The sign-in response was issued for another application.');
  }
  if (audiences.length > 1 && claims['azp'] !== expected.clientId) {
    throw new IdTokenError('The sign-in response was issued for another application.');
  }
  if (claims['nonce'] !== expected.nonce) {
    throw new IdTokenError('The sign-in response does not belong to this sign-in attempt.');
  }
  const exp = claims['exp'];
  if (typeof exp !== 'number' || exp + CLOCK_SKEW_SECONDS <= expected.nowSeconds) {
    throw new IdTokenError('The sign-in response has expired.');
  }
  const sub = claims['sub'];
  if (typeof sub !== 'string' || sub.trim() === '') {
    throw new IdTokenError('The sign-in response does not identify a user.');
  }
  const roles = claims['roles'];
  const role = Array.isArray(roles) ? ROLES.find((known) => roles.includes(known)) : undefined;
  if (!role) {
    throw new IdTokenError('This account has no role that the web app supports.');
  }
  const name = claims['name'];
  return { id: sub, name: typeof name === 'string' && name.trim() !== '' ? name : 'Signed-in user', role };
}

function decodeClaims(token: string): Record<string, unknown> {
  const parts = token.split('.');
  if (parts.length !== 3) {
    throw new IdTokenError('The sign-in response is malformed.');
  }
  try {
    const claims: unknown = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1])));
    if (typeof claims !== 'object' || claims === null || Array.isArray(claims)) {
      throw new IdTokenError('The sign-in response is malformed.');
    }
    return claims as Record<string, unknown>;
  } catch {
    throw new IdTokenError('The sign-in response is malformed.');
  }
}
