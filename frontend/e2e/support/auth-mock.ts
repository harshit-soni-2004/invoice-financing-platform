import { Page, Route } from '@playwright/test';

/** Must match public/config.json, which the app loads at startup. */
const ISSUER = 'http://localhost:9000';
const GATEWAY = 'http://localhost:8080';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
};

export interface MockUser {
  role: 'SELLER' | 'FINANCIER' | 'ADMIN';
  name: string;
  email: string;
}

export interface AuthMock {
  /** How many times the browser was sent to the authorization endpoint. */
  authorizeCount(): number;
  /** Authorization headers received by the mocked gateway. */
  apiAuthorizations(): string[];
  /** Makes the next authorization request come back with this OAuth2 error. */
  failNextAuthorize(error: string): void;
}

function unsignedJwt(claims: Record<string, unknown>): string {
  const part = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${part({ alg: 'RS256', kid: 'mock' })}.${part(claims)}.bW9jaw`;
}

/**
 * Stands in for auth-service and the gateway inside the browser: the authorization endpoint redirects straight back
 * with a code (as if the user had signed in), and the token endpoint answers with tokens for `user`.
 */
export async function mockAuth(page: Page, user: MockUser): Promise<AuthMock> {
  let authorizeCount = 0;
  let nonce = '';
  let nextError: string | null = null;
  const apiAuthorizations: string[] = [];

  await page.route(`${ISSUER}/**`, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    switch (url.pathname) {
      case '/.well-known/openid-configuration':
        return route.fulfill({
          headers: CORS,
          json: {
            issuer: ISSUER,
            authorization_endpoint: `${ISSUER}/oauth2/authorize`,
            token_endpoint: `${ISSUER}/oauth2/token`,
            end_session_endpoint: `${ISSUER}/connect/logout`,
          },
        });
      case '/oauth2/authorize': {
        authorizeCount++;
        nonce = url.searchParams.get('nonce') ?? '';
        const callback = new URL(url.searchParams.get('redirect_uri') ?? '');
        callback.searchParams.set('state', url.searchParams.get('state') ?? '');
        if (nextError) {
          callback.searchParams.set('error', nextError);
          nextError = null;
        } else {
          callback.searchParams.set('code', `mock-code-${authorizeCount}`);
        }
        return route.fulfill({ status: 302, headers: { Location: callback.toString() } });
      }
      case '/oauth2/token': {
        const body = new URLSearchParams(request.postData() ?? '');
        if (!body.get('code_verifier') || !body.get('code')?.startsWith('mock-code-')) {
          return route.fulfill({ status: 400, headers: CORS, json: { error: 'invalid_grant' } });
        }
        const now = Math.floor(Date.now() / 1000);
        return route.fulfill({
          headers: CORS,
          json: {
            access_token: `mock-access-${authorizeCount}`,
            id_token: unsignedJwt({
              iss: ISSUER,
              aud: ['ifp-spa'],
              sub: '7d4c2f9e-0000-4000-8000-000000000001',
              nonce,
              iat: now,
              exp: now + 1800,
              roles: [user.role],
              name: user.name,
            }),
            token_type: 'Bearer',
            expires_in: 900,
          },
        });
      }
      case '/connect/logout':
        return route.fulfill({
          status: 302,
          headers: { Location: url.searchParams.get('post_logout_redirect_uri') ?? '/' },
        });
      default:
        return route.fulfill({ status: 404, headers: CORS });
    }
  });

  await page.route(`${GATEWAY}/api/**`, async (route: Route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill({ status: 204, headers: CORS });
    }
    const authorization = (await request.headerValue('authorization')) ?? '';
    // The shell header checks for new notifications on every signed-in page. That background check gets an empty list
    // and stays out of apiAuthorizations, so assertions about a page's own requests are unaffected.
    if (new URL(request.url()).pathname === '/api/notifications' && authorization.startsWith('Bearer mock-access-')) {
      return route.fulfill({ headers: CORS, json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } });
    }
    apiAuthorizations.push(authorization);
    if (!authorization.startsWith('Bearer mock-access-')) {
      return route.fulfill({ status: 401, headers: CORS });
    }
    if (new URL(request.url()).pathname === '/api/users/me') {
      return route.fulfill({
        headers: CORS,
        json: {
          id: '7d4c2f9e-0000-4000-8000-000000000001',
          email: user.email,
          displayName: user.name,
          role: user.role,
          status: 'ACTIVE',
          createdAt: '2026-10-01T09:30:00Z',
        },
      });
    }
    return route.fulfill({ status: 404, headers: CORS });
  });

  return {
    authorizeCount: () => authorizeCount,
    apiAuthorizations: () => [...apiAuthorizations],
    failNextAuthorize: (error) => (nextError = error),
  };
}
