import { HttpErrorResponse } from '@angular/common/http';
import { Observable, firstValueFrom } from 'rxjs';

/**
 * Failures of the gateway APIs other than invoice-service, which has its own mapping in the seller feature. Only the
 * problem-details `code` and, for conflicts, `currentStatus` are read; server wording is never shown.
 */
export type ApiFailure =
  | { kind: 'invalid' }
  | { kind: 'conflict'; code: string | null; currentStatus: string | null }
  | { kind: 'stale' }
  | { kind: 'unauthenticated' }
  | { kind: 'forbidden' }
  | { kind: 'not-found' }
  | { kind: 'rate-limited'; retryAfterSeconds: number | null }
  | { kind: 'network' }
  | { kind: 'unavailable' }
  | { kind: 'unexpected' };

export class ApiError extends Error {
  constructor(readonly failure: ApiFailure) {
    super(`API request failed: ${failure.kind}`);
  }
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export async function callApi<T>(request: Observable<T>): Promise<T> {
  try {
    return await firstValueFrom(request);
  } catch (error) {
    throw new ApiError(error instanceof HttpErrorResponse ? apiFailureFor(error) : { kind: 'unexpected' });
  }
}

export function failureOf(error: unknown): ApiFailure {
  return error instanceof ApiError ? error.failure : { kind: 'unexpected' };
}

export function apiFailureFor(error: HttpErrorResponse): ApiFailure {
  const body: Record<string, unknown> = isObject(error.error) ? error.error : {};
  switch (error.status) {
    case 0:
      return { kind: 'network' };
    case 400:
      return { kind: 'invalid' };
    case 401:
      return { kind: 'unauthenticated' };
    case 403:
      return { kind: 'forbidden' };
    case 404:
      return { kind: 'not-found' };
    case 409:
      return { kind: 'conflict', code: textOrNull(body['code']), currentStatus: textOrNull(body['currentStatus']) };
    case 412:
    case 428:
      return { kind: 'stale' };
    case 429: {
      const header = error.headers.get('Retry-After')?.trim() ?? '';
      return { kind: 'rate-limited', retryAfterSeconds: /^\d+$/.test(header) ? Number(header) : null };
    }
    case 502:
    case 503:
    case 504:
      return { kind: 'unavailable' };
    default:
      return { kind: 'unexpected' };
  }
}

/** Messages for the failures every page words the same way; `service` names what was unavailable. */
export function commonFailureMessage(failure: ApiFailure, service: string): string {
  switch (failure.kind) {
    case 'invalid':
      return 'The request was not accepted. Check what you entered and try again.';
    case 'conflict':
      return 'This was changed by someone else at the same time. Refresh and try again.';
    case 'stale':
      return 'This changed since you opened it. Refresh and try again.';
    case 'unauthenticated':
      return 'Your session has ended. Sign in again to continue.';
    case 'forbidden':
      return 'You do not have permission to do this.';
    case 'not-found':
      return 'It no longer exists.';
    case 'rate-limited':
      return failure.retryAfterSeconds !== null && failure.retryAfterSeconds > 1
        ? `Too many requests. Wait ${failure.retryAfterSeconds} seconds and try again.`
        : 'Too many requests. Wait a moment and try again.';
    case 'network':
      return 'The server could not be reached. Check your connection and try again.';
    case 'unavailable':
      return `The ${service} is temporarily unavailable. Try again in a few minutes.`;
    case 'unexpected':
      return 'Something went wrong. Try again in a moment.';
  }
}

/** Reads the `{ content, page, size, totalElements, totalPages }` contract the services share; null if malformed. */
export function parsePage<T>(body: unknown, parseRow: (row: unknown) => T | null): Page<T> | null {
  if (!isObject(body) || !Array.isArray(body['content'])) {
    return null;
  }
  const { page, size, totalElements, totalPages } = body;
  if (!isCount(page) || !isCount(size) || !isCount(totalElements) || !isCount(totalPages)) {
    return null;
  }
  const content: T[] = [];
  for (const row of body['content']) {
    const parsed = parseRow(row);
    if (parsed === null) {
      return null;
    }
    content.push(parsed);
  }
  return { content, page, size, totalElements, totalPages };
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

export function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

export function textOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}
