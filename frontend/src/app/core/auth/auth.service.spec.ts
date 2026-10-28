import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { APP_CONFIG } from '../config/app-config';
import { TEST_CONFIG, TEST_DISCOVERY, fakeJwt } from './auth-testing';
import { AuthError, AuthService, BROWSER_LOCATION, PENDING_SIGN_IN_KEY, safeReturnUrl } from './auth.service';
import { s256Challenge } from './pkce';

const DISCOVERY_URL = 'http://auth.test/.well-known/openid-configuration';

async function settle(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let router: Router;
  let location: { assign: jasmine.Spy; origin: string };

  beforeEach(() => {
    sessionStorage.removeItem(PENDING_SIGN_IN_KEY);
    location = { assign: jasmine.createSpy('assign'), origin: 'http://app.test' };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: BROWSER_LOCATION, useValue: location },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.removeItem(PENDING_SIGN_IN_KEY);
  });

  async function startSignIn(returnUrl?: string): Promise<URL> {
    const started = auth.signIn(returnUrl);
    http.expectOne(DISCOVERY_URL).flush(TEST_DISCOVERY);
    await started;
    return new URL(location.assign.calls.mostRecent().args[0] as string);
  }

  async function signIn(
    claims: Record<string, unknown> = {},
    returnUrl?: string,
    tokenResponse: Record<string, unknown> = {},
  ): Promise<string> {
    const authorize = await startSignIn(returnUrl);
    const done = auth.completeSignIn({ code: 'code-1', state: authorize.searchParams.get('state') ?? '' });
    await settle();
    http.expectOne(TEST_DISCOVERY.token_endpoint).flush({
      access_token: 'access-1',
      id_token: fakeJwt({
        iss: 'http://auth.test',
        aud: ['ifp-spa'],
        sub: 'user-1',
        nonce: authorize.searchParams.get('nonce'),
        exp: Math.floor(Date.now() / 1000) + 1800,
        roles: ['SELLER'],
        name: 'Asha Rao',
        ...claims,
      }),
      token_type: 'Bearer',
      expires_in: 900,
      ...tokenResponse,
    });
    return done;
  }

  it('redirects to the authorization endpoint with PKCE S256, state and nonce', async () => {
    const url = await startSignIn('/account');

    expect(`${url.origin}${url.pathname}`).toBe(TEST_DISCOVERY.authorization_endpoint);
    const params = url.searchParams;
    expect(params.get('response_type')).toBe('code');
    expect(params.get('client_id')).toBe('ifp-spa');
    expect(params.get('redirect_uri')).toBe('http://app.test/auth/callback');
    expect(params.get('scope')).toBe('openid profile');
    expect(params.get('code_challenge_method')).toBe('S256');

    const pending = JSON.parse(sessionStorage.getItem(PENDING_SIGN_IN_KEY) ?? '{}');
    expect(params.get('state')).toBe(pending.state);
    expect(params.get('nonce')).toBe(pending.nonce);
    expect(params.get('code_challenge')).toBe(await s256Challenge(pending.verifier));
    expect(params.get('code_challenge')).not.toBe(pending.verifier);
    expect(pending.returnUrl).toBe('/account');
  });

  it('drops return URLs that leave the app', () => {
    expect(safeReturnUrl('/seller?x=1')).toBe('/seller?x=1');
    for (const url of ['https://evil.test/', '//evil.test', '/\\evil.test', 'seller', '/login?returnUrl=/x', '/auth/callback']) {
      expect(safeReturnUrl(url)).withContext(url).toBeUndefined();
    }
  });

  it('refuses a discovery document from another issuer', async () => {
    const started = auth.signIn();
    http.expectOne(DISCOVERY_URL).flush({ ...TEST_DISCOVERY, issuer: 'http://evil.test' });
    await expectAsync(started).toBeRejectedWithError(AuthError);
    expect(location.assign).not.toHaveBeenCalled();
  });

  it('refuses endpoints on another origin', async () => {
    const started = auth.signIn();
    http.expectOne(DISCOVERY_URL).flush({ ...TEST_DISCOVERY, token_endpoint: 'http://evil.test/token' });
    await expectAsync(started).toBeRejectedWithError(AuthError);
  });

  it('retries discovery after a failure', async () => {
    const failed = auth.signIn();
    http.expectOne(DISCOVERY_URL).flush('down', { status: 503, statusText: 'Unavailable' });
    await expectAsync(failed).toBeRejectedWithError(AuthError, /unavailable/);

    await startSignIn();
    expect(location.assign).toHaveBeenCalledTimes(1);
  });

  it('exchanges the code with the verifier and starts an in-memory session', async () => {
    const authorize = await startSignIn('/account');
    const verifier = JSON.parse(sessionStorage.getItem(PENDING_SIGN_IN_KEY) ?? '{}').verifier;
    const done = auth.completeSignIn({ code: 'code-1', state: authorize.searchParams.get('state') ?? '' });
    expect(sessionStorage.getItem(PENDING_SIGN_IN_KEY)).toBeNull();
    await settle();

    const request = http.expectOne(TEST_DISCOVERY.token_endpoint);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    const body = new URLSearchParams(request.request.body as string);
    expect(body.get('grant_type')).toBe('authorization_code');
    expect(body.get('code')).toBe('code-1');
    expect(body.get('code_verifier')).toBe(verifier);
    expect(body.get('redirect_uri')).toBe('http://app.test/auth/callback');
    expect(body.get('client_id')).toBe('ifp-spa');
    request.flush({
      access_token: 'access-1',
      id_token: fakeJwt({
        iss: 'http://auth.test',
        aud: 'ifp-spa',
        sub: 'user-1',
        nonce: authorize.searchParams.get('nonce'),
        exp: Math.floor(Date.now() / 1000) + 1800,
        roles: ['FINANCIER'],
        name: 'Vikram Shah',
      }),
      token_type: 'Bearer',
      expires_in: 900,
    });

    expect(await done).toBe('/account');
    expect(auth.user()).toEqual({ id: 'user-1', name: 'Vikram Shah', role: 'FINANCIER' });
    expect(Object.keys(localStorage).some((key) => key.startsWith('ifp.auth'))).toBeFalse();
    expect(Object.keys(sessionStorage).some((key) => key.startsWith('ifp.auth'))).toBeFalse();
  });

  it("continues to the role's home page when no return URL was given", async () => {
    expect(await signIn({ roles: ['ADMIN'] })).toBe('/admin');
  });

  it('gives the token to gateway API calls only', async () => {
    await signIn();
    expect(auth.accessTokenFor('http://gateway.test/api/invoices')).toBe('access-1');
    expect(auth.accessTokenFor('http://gateway.test/actuator/health')).toBeNull();
    expect(auth.accessTokenFor('http://auth.test/userinfo')).toBeNull();
    expect(auth.accessTokenFor('http://gateway.test.evil.test/api/invoices')).toBeNull();
  });

  it('rejects a response for another sign-in attempt without calling the token endpoint', async () => {
    await startSignIn();
    await expectAsync(auth.completeSignIn({ code: 'code-1', state: 'forged' })).toBeRejectedWithError(AuthError);
    expect(sessionStorage.getItem(PENDING_SIGN_IN_KEY)).toBeNull();
    expect(auth.isSignedIn()).toBeFalse();
  });

  it('rejects a callback with no sign-in in progress', async () => {
    await expectAsync(auth.completeSignIn({ code: 'code-1', state: 's' })).toBeRejectedWithError(
      AuthError,
      /no longer valid/,
    );
  });

  it('reports an error returned by the sign-in service', async () => {
    await startSignIn();
    await expectAsync(auth.completeSignIn({ error: 'access_denied' })).toBeRejectedWithError(AuthError, /cancelled/);
    expect(sessionStorage.getItem(PENDING_SIGN_IN_KEY)).toBeNull();
  });

  it('rejects an ID token with the wrong nonce', async () => {
    await expectAsync(signIn({ nonce: 'replayed' })).toBeRejectedWithError(AuthError, /does not belong/);
    expect(auth.isSignedIn()).toBeFalse();
  });

  it('rejects a token response that is not a bearer token', async () => {
    await expectAsync(signIn({}, undefined, { token_type: 'DPoP' })).toBeRejectedWithError(AuthError);
    expect(auth.isSignedIn()).toBeFalse();
  });

  it('reports a refused code exchange', async () => {
    const authorize = await startSignIn();
    const done = auth.completeSignIn({ code: 'used', state: authorize.searchParams.get('state') ?? '' });
    await settle();
    http
      .expectOne(TEST_DISCOVERY.token_endpoint)
      .flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });
    await expectAsync(done).toBeRejectedWithError(AuthError, /did not accept/);
  });

  it('ends the session shortly before the access token expires', async () => {
    jasmine.clock().install();
    try {
      jasmine.clock().mockDate(new Date());
      await signIn();
      jasmine.clock().tick(869_000);
      expect(auth.isSignedIn()).toBeTrue();

      jasmine.clock().tick(1_000);
      expect(auth.isSignedIn()).toBeFalse();
      expect(auth.accessTokenFor('http://gateway.test/api/invoices')).toBeNull();
      expect(router.navigate).toHaveBeenCalledWith(['/login'], jasmine.objectContaining({
        queryParams: jasmine.objectContaining({ reason: 'expired' }),
      }));
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('signs in again when the gateway rejects the token', async () => {
    await signIn();
    auth.sessionRejected();
    expect(auth.isSignedIn()).toBeFalse();
    expect(router.navigate).toHaveBeenCalledWith(['/login'], jasmine.any(Object));
  });

  it('signs out of auth-service with the ID token as a hint', async () => {
    await signIn();
    location.assign.calls.reset();

    await auth.signOut();

    expect(auth.isSignedIn()).toBeFalse();
    const url = new URL(location.assign.calls.mostRecent().args[0] as string);
    expect(`${url.origin}${url.pathname}`).toBe(TEST_DISCOVERY.end_session_endpoint);
    expect(url.searchParams.get('id_token_hint')).toMatch(/^[\w-]+\.[\w-]+\.[\w-]+$/);
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe('http://app.test/');
    expect(url.searchParams.get('client_id')).toBe('ifp-spa');
  });
});
