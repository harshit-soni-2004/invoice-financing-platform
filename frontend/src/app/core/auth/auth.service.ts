import { DOCUMENT, DestroyRef, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../config/app-config';
import { IdTokenError, Role, SessionUser, readIdToken } from './id-token';
import { randomUrlSafe, s256Challenge } from './pkce';

/** The parts of `window.location` the sign-in flow needs; replaced in tests so nothing navigates away. */
export type BrowserLocation = Pick<Location, 'assign' | 'origin'>;

export const BROWSER_LOCATION = new InjectionToken<BrowserLocation>('BROWSER_LOCATION', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).location,
});

/** Holds only the short-lived values that must survive the redirect to auth-service, never tokens. */
export const AUTH_REDIRECT_STORAGE = new InjectionToken<Storage>('AUTH_REDIRECT_STORAGE', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).defaultView!.sessionStorage,
});

export const PENDING_SIGN_IN_KEY = 'ifp.auth.pending';
export const CALLBACK_PATH = '/auth/callback';

/** How long an unfinished sign-in stays usable; auth-service's authorization codes live for 5 minutes. */
const PENDING_TTL_MS = 10 * 60 * 1000;
/** Treat the access token as expired slightly early, so a request is not sent just as it lapses. */
const EXPIRY_MARGIN_MS = 30 * 1000;

export class AuthError extends Error {}

export interface CallbackParams {
  code?: string;
  state?: string;
  error?: string;
}

interface OidcMetadata {
  issuer: string;
  authorizationEndpoint: string;
  tokenEndpoint: string;
  endSessionEndpoint?: string;
}

interface PendingSignIn {
  state: string;
  nonce: string;
  verifier: string;
  returnUrl?: string;
  createdAt: number;
}

interface Session {
  accessToken: string;
  idToken: string;
  expiresAt: number;
  user: SessionUser;
}

export function homeFor(role: Role): string {
  switch (role) {
    case 'SELLER':
      return '/seller';
    case 'FINANCIER':
      return '/financier';
    case 'ADMIN':
      return '/admin';
  }
}

/** Only in-app paths are accepted as return URLs, so a crafted link cannot send the user to another site. */
export function safeReturnUrl(value: string | null | undefined): string | undefined {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return undefined;
  }
  if (value.startsWith('/login') || value.startsWith(CALLBACK_PATH)) {
    return undefined;
  }
  return value;
}

/**
 * Authorization Code + PKCE against auth-service for the public client. Tokens live in memory only: a reload signs the
 * user out of the app, and the next guarded page goes back through auth-service, whose own session skips the password.
 * auth-service issues no refresh tokens, so the session ends when the access token expires.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(APP_CONFIG);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly location = inject(BROWSER_LOCATION);
  private readonly storage = inject(AUTH_REDIRECT_STORAGE);

  private readonly session = signal<Session | null>(null);
  private discovery?: Promise<OidcMetadata>;
  private expiryTimer?: ReturnType<typeof setTimeout>;

  readonly user = computed(() => this.session()?.user ?? null);
  readonly isSignedIn = computed(() => this.session() !== null);

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.expiryTimer));
  }

  /** Redirects the browser to auth-service. Resolves only if the redirect could not be started. */
  async signIn(returnUrl?: string): Promise<void> {
    const metadata = await this.metadata();
    const pending: PendingSignIn = {
      state: randomUrlSafe(),
      nonce: randomUrlSafe(),
      verifier: randomUrlSafe(),
      returnUrl: safeReturnUrl(returnUrl),
      createdAt: Date.now(),
    };
    const challenge = await s256Challenge(pending.verifier);
    this.storage.setItem(PENDING_SIGN_IN_KEY, JSON.stringify(pending));

    const url = new URL(metadata.authorizationEndpoint);
    url.search = new URLSearchParams({
      response_type: 'code',
      client_id: this.config.clientId,
      redirect_uri: this.redirectUri(),
      scope: 'openid profile',
      state: pending.state,
      nonce: pending.nonce,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    }).toString();
    this.location.assign(url.toString());
  }

  /** Finishes a sign-in at the callback and returns the in-app URL to continue to. */
  async completeSignIn(params: CallbackParams): Promise<string> {
    const pending = this.takePending();
    if (params.error) {
      throw new AuthError(
        params.error === 'access_denied'
          ? 'Sign-in was cancelled or refused.'
          : 'The sign-in service could not complete the sign-in.',
      );
    }
    if (!pending || Date.now() - pending.createdAt > PENDING_TTL_MS) {
      throw new AuthError('This sign-in attempt is no longer valid. Please start again.');
    }
    if (!params.state || params.state !== pending.state) {
      throw new AuthError('This sign-in response does not belong to this browser tab. Please start again.');
    }
    if (!params.code) {
      throw new AuthError('The sign-in response is incomplete. Please start again.');
    }

    const metadata = await this.metadata();
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      code: params.code,
      redirect_uri: this.redirectUri(),
      client_id: this.config.clientId,
      code_verifier: pending.verifier,
    }).toString();
    let response: Record<string, unknown>;
    try {
      response = await firstValueFrom(
        this.http.post<Record<string, unknown>>(metadata.tokenEndpoint, body, {
          headers: new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
        }),
      );
    } catch {
      throw new AuthError('The sign-in service did not accept the sign-in. Please start again.');
    }

    const accessToken = response['access_token'];
    const idToken = response['id_token'];
    const tokenType = response['token_type'];
    const expiresIn = response['expires_in'];
    if (
      typeof accessToken !== 'string' ||
      typeof idToken !== 'string' ||
      typeof tokenType !== 'string' ||
      tokenType.toLowerCase() !== 'bearer' ||
      typeof expiresIn !== 'number' ||
      expiresIn <= 0
    ) {
      throw new AuthError('The sign-in service returned an unexpected response.');
    }

    let user: SessionUser;
    try {
      user = readIdToken(idToken, {
        issuer: metadata.issuer,
        clientId: this.config.clientId,
        nonce: pending.nonce,
        nowSeconds: Math.floor(Date.now() / 1000),
      });
    } catch (error) {
      throw new AuthError(error instanceof IdTokenError ? error.message : 'The sign-in response could not be read.');
    }

    this.startSession({ accessToken, idToken, expiresAt: Date.now() + expiresIn * 1000, user });
    return pending.returnUrl ?? homeFor(user.role);
  }

  /** The bearer token for a request, or null. Only gateway API calls ever receive it. */
  accessTokenFor(url: string): string | null {
    const session = this.session();
    if (!session || Date.now() >= session.expiresAt || !url.startsWith(`${this.config.apiBaseUrl}/api/`)) {
      return null;
    }
    return session.accessToken;
  }

  /** Shows a name the user just saved; the ID token keeps the old one until the next sign-in. */
  renameUser(name: string): void {
    this.session.update((session) => (session ? { ...session, user: { ...session.user, name } } : session));
  }

  /** The gateway rejected the token (for example after a role change or key rotation): sign in again. */
  sessionRejected(): void {
    if (!this.session()) {
      return;
    }
    this.endSession();
    void this.router.navigate(['/login'], { queryParams: { returnUrl: safeReturnUrl(this.router.url) } });
  }

  /** Ends the app session, then ends the auth-service session so the next sign-in asks for the password again. */
  async signOut(): Promise<void> {
    const session = this.session();
    this.endSession();
    let endSessionEndpoint: string | undefined;
    try {
      endSessionEndpoint = (await this.metadata()).endSessionEndpoint;
    } catch {
      endSessionEndpoint = undefined;
    }
    if (!session || !endSessionEndpoint) {
      await this.router.navigateByUrl('/');
      return;
    }
    const url = new URL(endSessionEndpoint);
    url.search = new URLSearchParams({
      id_token_hint: session.idToken,
      client_id: this.config.clientId,
      post_logout_redirect_uri: `${this.location.origin}/`,
    }).toString();
    this.location.assign(url.toString());
  }

  private startSession(session: Session): void {
    clearTimeout(this.expiryTimer);
    this.session.set(session);
    const delay = Math.max(0, session.expiresAt - Date.now() - EXPIRY_MARGIN_MS);
    this.expiryTimer = setTimeout(() => this.expire(), delay);
  }

  private expire(): void {
    this.endSession();
    void this.router.navigate(['/login'], {
      queryParams: { reason: 'expired', returnUrl: safeReturnUrl(this.router.url) },
    });
  }

  private endSession(): void {
    clearTimeout(this.expiryTimer);
    this.expiryTimer = undefined;
    this.session.set(null);
  }

  private redirectUri(): string {
    return `${this.location.origin}${CALLBACK_PATH}`;
  }

  private takePending(): PendingSignIn | null {
    const raw = this.storage.getItem(PENDING_SIGN_IN_KEY);
    this.storage.removeItem(PENDING_SIGN_IN_KEY);
    if (!raw) {
      return null;
    }
    try {
      const value = JSON.parse(raw) as Partial<PendingSignIn>;
      if (
        typeof value.state !== 'string' ||
        typeof value.nonce !== 'string' ||
        typeof value.verifier !== 'string' ||
        typeof value.createdAt !== 'number'
      ) {
        return null;
      }
      return {
        state: value.state,
        nonce: value.nonce,
        verifier: value.verifier,
        returnUrl: safeReturnUrl(value.returnUrl),
        createdAt: value.createdAt,
      };
    } catch {
      return null;
    }
  }

  private metadata(): Promise<OidcMetadata> {
    this.discovery ??= this.discover().catch((error: unknown) => {
      this.discovery = undefined;
      throw error;
    });
    return this.discovery;
  }

  private async discover(): Promise<OidcMetadata> {
    let raw: Record<string, unknown>;
    try {
      raw = await firstValueFrom(
        this.http.get<Record<string, unknown>>(`${this.config.authIssuer}/.well-known/openid-configuration`),
      );
    } catch {
      throw new AuthError('The sign-in service is unavailable. Please try again in a moment.');
    }
    const issuer = raw['issuer'];
    if (typeof issuer !== 'string' || issuer.replace(/\/+$/, '') !== this.config.authIssuer) {
      throw new AuthError('The sign-in service is misconfigured (unexpected issuer).');
    }
    const origin = new URL(this.config.authIssuer).origin;
    return {
      issuer,
      authorizationEndpoint: endpoint(raw['authorization_endpoint'], origin),
      tokenEndpoint: endpoint(raw['token_endpoint'], origin),
      endSessionEndpoint:
        raw['end_session_endpoint'] === undefined ? undefined : endpoint(raw['end_session_endpoint'], origin),
    };
  }
}

/** Endpoints must be on the issuer's own origin, so a tampered discovery document cannot redirect codes or tokens. */
function endpoint(value: unknown, origin: string): string {
  if (typeof value === 'string') {
    try {
      if (new URL(value).origin === origin) {
        return value;
      }
    } catch {
      // Reported below.
    }
  }
  throw new AuthError('The sign-in service is misconfigured (unexpected endpoint).');
}
