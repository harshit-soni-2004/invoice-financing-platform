import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

import { Role, ROLES } from '../../core/auth/id-token';
import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError, Page, callApi, isObject, isText, parsePage } from '../../shared/api-failure';

export const USER_PAGE_SIZE = 20;

export type UserStatus = 'ACTIVE' | 'PENDING_APPROVAL' | 'DISABLED' | 'REJECTED';

const USER_STATUSES: readonly UserStatus[] = ['ACTIVE', 'PENDING_APPROVAL', 'DISABLED', 'REJECTED'];

/** auth-service's `RejectUserRequest` limit. */
export const REJECT_USER_REASON_MAX = 500;

/** auth-service's `UserResponse`. */
export interface AdminUser {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  status: UserStatus;
  createdAt: string;
}

export function userStatusOf(value: unknown): UserStatus | null {
  return USER_STATUSES.find((status) => status === value) ?? null;
}

/**
 * auth-service's administrator endpoints, through the gateway: listing, activation and rejection of pending accounts.
 * There is no endpoint to disable an account.
 */
@Injectable({ providedIn: 'root' })
export class AdminUsersService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/admin/users`;

  /** Financier accounts waiting for approval, newest first (auth-service's fixed order). `page` is zero-based. */
  async pendingFinanciers(page: number): Promise<Page<AdminUser>> {
    const params = new HttpParams()
      .set('role', 'FINANCIER')
      .set('status', 'PENDING_APPROVAL')
      .set('page', page)
      .set('size', USER_PAGE_SIZE);
    const result = parsePage(await callApi(this.http.get<unknown>(this.url, { params })), parseUser);
    if (!result) {
      throw new ApiError({ kind: 'unexpected' });
    }
    return result;
  }

  /** Approves a pending account. auth-service answers 409 `USER_NOT_PENDING_APPROVAL` if it is no longer pending. */
  async activate(id: string): Promise<AdminUser> {
    const user = parseUser(await callApi(this.http.post<unknown>(`${this.url}/${encodeURIComponent(id)}/activate`, null)));
    if (!user) {
      throw new ApiError({ kind: 'unexpected' });
    }
    return user;
  }

  /**
   * Rejects a pending account for good; a rejected account can never sign in or be approved. The reason is optional.
   * auth-service answers 409 `USER_NOT_PENDING_APPROVAL` if the account is no longer pending.
   */
  async reject(id: string, reason: string | null): Promise<AdminUser> {
    const body = reason === null ? {} : { reason };
    const user = parseUser(await callApi(this.http.post<unknown>(`${this.url}/${encodeURIComponent(id)}/reject`, body)));
    if (!user) {
      throw new ApiError({ kind: 'unexpected' });
    }
    return user;
  }
}

function parseUser(row: unknown): AdminUser | null {
  if (!isObject(row)) {
    return null;
  }
  const { id, email, displayName, createdAt } = row;
  const role = ROLES.find((r) => r === row['role']);
  const status = userStatusOf(row['status']);
  if (!isText(id) || !isText(email) || !isText(displayName) || !isText(createdAt) || !role || !status) {
    return null;
  }
  return { id, email, displayName, role, status, createdAt };
}
