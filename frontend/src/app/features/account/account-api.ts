import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { Role } from '../../core/auth/id-token';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiFailure, apiFailureFor, isObject, textOrNull } from '../../shared/api-failure';

export interface Profile {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  status: string;
  createdAt: string;
}

/** A request auth-service rejected because of one field; `message` is the server's short reason for that field. */
export interface FieldRejection {
  field: string;
  message: string;
}

export class AccountApiError extends Error {
  constructor(
    readonly failure: ApiFailure,
    readonly rejections: readonly FieldRejection[] = [],
  ) {
    super(`Account request failed: ${failure.kind}`);
  }
}

/** The signed-in user's own account on auth-service, through the gateway. */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiBaseUrl}/api/users/me`;

  load(): Promise<Profile> {
    return call(this.http.get<Profile>(this.base));
  }

  /** Only the display name can change; email, role and status are managed elsewhere. */
  rename(displayName: string): Promise<Profile> {
    return call(this.http.put<Profile>(this.base, { displayName }));
  }

  changePassword(currentPassword: string, newPassword: string): Promise<void> {
    return call(this.http.post<void>(`${this.base}/password`, { currentPassword, newPassword }));
  }
}

async function call<T>(request: Observable<T>): Promise<T> {
  try {
    return await firstValueFrom(request);
  } catch (error) {
    if (!(error instanceof HttpErrorResponse)) {
      throw new AccountApiError({ kind: 'unexpected' });
    }
    throw new AccountApiError(apiFailureFor(error), error.status === 400 ? rejectionsIn(error.error) : []);
  }
}

function rejectionsIn(body: unknown): FieldRejection[] {
  if (!isObject(body) || !Array.isArray(body['errors'])) {
    return [];
  }
  return body['errors'].flatMap((entry: unknown) => {
    if (!isObject(entry)) {
      return [];
    }
    const field = textOrNull(entry['field']);
    const message = textOrNull(entry['message']);
    return field && message ? [{ field, message }] : [];
  });
}
