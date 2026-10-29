import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError, Page, callApi, isObject, isText, parsePage, textOrNull } from '../../shared/api-failure';

export const AUDIT_PAGE_SIZE = 20;
/** audit-service rejects pages beyond this. */
export const AUDIT_MAX_PAGE = 10_000;

/** audit-service's own patterns for the filter parameters. */
export const AUDIT_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_.]{0,59}$/;
export const AUDIT_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;
export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** `from` is inclusive and `to` exclusive; both are ISO-8601 instants. */
export interface AuditFilter {
  eventType: string | null;
  aggregateType: string | null;
  aggregateId: string | null;
  actorId: string | null;
  from: string | null;
  to: string | null;
}

/** One row of `GET /api/audit-events`. The payload was sanitized by audit-service before it was stored. */
export interface AuditEvent {
  id: number;
  eventId: string;
  eventType: string;
  schemaVersion: number;
  producer: string;
  aggregateType: string;
  aggregateId: string;
  actorId: string | null;
  correlationId: string | null;
  occurredAt: string;
  recordedAt: string;
  payload: unknown;
}

/** audit-service's read-only search, through the gateway. Both the gateway and audit-service require ADMIN. */
@Injectable({ providedIn: 'root' })
export class AuditApiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/audit-events`;

  /** Newest first. `page` is zero-based. */
  async search(filter: AuditFilter, page: number): Promise<Page<AuditEvent>> {
    let params = new HttpParams().set('page', page).set('size', AUDIT_PAGE_SIZE);
    for (const [name, value] of Object.entries(filter) as [keyof AuditFilter, string | null][]) {
      if (value) {
        params = params.set(name, value);
      }
    }
    const result = parsePage(await callApi(this.http.get<unknown>(this.url, { params })), parseEvent);
    if (!result) {
      throw new ApiError({ kind: 'unexpected' });
    }
    return result;
  }
}

function parseEvent(row: unknown): AuditEvent | null {
  if (!isObject(row)) {
    return null;
  }
  const { id, eventId, eventType, schemaVersion, producer, aggregateType, aggregateId, occurredAt, recordedAt } = row;
  if (
    typeof id !== 'number' ||
    typeof schemaVersion !== 'number' ||
    !isText(eventId) ||
    !isText(eventType) ||
    !isText(producer) ||
    !isText(aggregateType) ||
    !isText(aggregateId) ||
    !isText(occurredAt) ||
    !isText(recordedAt)
  ) {
    return null;
  }
  return {
    id,
    eventId,
    eventType,
    schemaVersion,
    producer,
    aggregateType,
    aggregateId,
    actorId: textOrNull(row['actorId']),
    correlationId: textOrNull(row['correlationId']),
    occurredAt,
    recordedAt,
    payload: row['payload'] ?? null,
  };
}
