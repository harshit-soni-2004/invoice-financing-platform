import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Role } from '../../core/auth/id-token';
import { APP_CONFIG } from '../../core/config/app-config';

/** auth-service refuses ADMIN at public registration; the app never offers it. */
export type SelfRegistrableRole = Extract<Role, 'SELLER' | 'FINANCIER'>;

export const SELF_REGISTRABLE_ROLES: readonly SelfRegistrableRole[] = ['SELLER', 'FINANCIER'];

export interface RegistrationRequest {
  email: string;
  password: string;
  displayName: string;
  role: SelfRegistrableRole;
}

export type AccountStatus = 'ACTIVE' | 'PENDING_APPROVAL';

export interface RegisteredAccount {
  id: string;
  email: string;
  displayName: string;
  role: SelfRegistrableRole;
  status: AccountStatus;
}

export type RegistrationField = keyof RegistrationRequest;

export type RegistrationFailure =
  | { kind: 'invalid'; fieldErrors: Partial<Record<RegistrationField, string>> }
  | { kind: 'duplicate-email' }
  | { kind: 'rate-limited'; retryAfterSeconds: number | null }
  | { kind: 'network' }
  | { kind: 'unavailable' }
  | { kind: 'unexpected' };

export class RegistrationError extends Error {
  constructor(readonly failure: RegistrationFailure) {
    super(`Registration failed: ${failure.kind}`);
  }
}

const FIELDS: readonly RegistrationField[] = ['email', 'password', 'displayName', 'role'];

/** Public sign-up through the gateway's `POST /api/auth/register`. It creates an account but does not sign in. */
@Injectable({ providedIn: 'root' })
export class RegistrationService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/auth/register`;

  async register(request: RegistrationRequest): Promise<RegisteredAccount> {
    let body: unknown;
    try {
      body = await firstValueFrom(this.http.post<unknown>(this.url, request));
    } catch (error) {
      throw new RegistrationError(error instanceof HttpErrorResponse ? failureFor(error) : { kind: 'unexpected' });
    }
    const account = parseAccount(body, request.role);
    if (!account) {
      throw new RegistrationError({ kind: 'unexpected' });
    }
    return account;
  }
}

function failureFor(error: HttpErrorResponse): RegistrationFailure {
  switch (error.status) {
    case 0:
      return { kind: 'network' };
    case 400:
      return { kind: 'invalid', fieldErrors: fieldErrors(error.error) };
    case 409:
      return isObject(error.error) && error.error['code'] === 'DUPLICATE_EMAIL'
        ? { kind: 'duplicate-email' }
        : { kind: 'unexpected' };
    case 429:
      return { kind: 'rate-limited', retryAfterSeconds: retryAfter(error.headers.get('Retry-After')) };
    case 502:
    case 503:
    case 504:
      return { kind: 'unavailable' };
    default:
      return { kind: 'unexpected' };
  }
}

/** Reads `errors: [{ field, message }]` from auth-service's problem details, keeping only known fields. */
function fieldErrors(body: unknown): Partial<Record<RegistrationField, string>> {
  const result: Partial<Record<RegistrationField, string>> = {};
  const errors = isObject(body) ? body['errors'] : undefined;
  if (!Array.isArray(errors)) {
    return result;
  }
  for (const entry of errors) {
    if (!isObject(entry)) {
      continue;
    }
    const field = FIELDS.find((name) => name === entry['field']);
    const message = entry['message'];
    if (field && typeof message === 'string' && result[field] === undefined) {
      result[field] = message;
    }
  }
  return result;
}

function retryAfter(header: string | null): number | null {
  if (header === null || !/^\d+$/.test(header.trim())) {
    return null;
  }
  return Number(header.trim());
}

function parseAccount(body: unknown, requestedRole: SelfRegistrableRole): RegisteredAccount | null {
  if (!isObject(body)) {
    return null;
  }
  const { id, email, displayName, role, status } = body;
  if (
    typeof id !== 'string' ||
    typeof email !== 'string' ||
    typeof displayName !== 'string' ||
    role !== requestedRole ||
    (status !== 'ACTIVE' && status !== 'PENDING_APPROVAL')
  ) {
    return null;
  }
  return { id, email, displayName, role: requestedRole, status };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
