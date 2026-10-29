import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { ApiError, Page, callApi, isObject, isText, parsePage, textOrNull } from '../../shared/api-failure';

export const NOTIFICATION_PAGE_SIZE = 20;

/** Whether notification-service emailed the message, or why it did not. There is no read/unread state. */
export type DeliveryStatus = 'SENT' | 'SKIPPED';

/** One row of `GET /api/notifications`. Message bodies are not stored, only the subject. */
export interface NotificationItem {
  id: string;
  eventType: string;
  subject: string;
  status: DeliveryStatus;
  skipReason: string | null;
  createdAt: string;
  sentAt: string | null;
}

/** notification-service through the gateway. The owner is always the token's user; the browser never names one. */
@Injectable({ providedIn: 'root' })
export class NotificationApiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiBaseUrl}/api/notifications`;

  /** The caller's notifications, newest first. `page` is zero-based. */
  async list(page: number, size = NOTIFICATION_PAGE_SIZE): Promise<Page<NotificationItem>> {
    const params = new HttpParams().set('page', page).set('size', size);
    const result = parsePage(await callApi(this.http.get<unknown>(this.url, { params })), parseNotification);
    if (!result) {
      throw new ApiError({ kind: 'unexpected' });
    }
    return result;
  }
}

function parseNotification(row: unknown): NotificationItem | null {
  if (!isObject(row)) {
    return null;
  }
  const { id, eventType, subject, createdAt } = row;
  const status = row['status'] === 'SENT' || row['status'] === 'SKIPPED' ? row['status'] : null;
  if (!isText(id) || !isText(eventType) || !isText(subject) || !isText(createdAt) || !status) {
    return null;
  }
  return { id, eventType, subject, status, skipReason: textOrNull(row['skipReason']), createdAt, sentAt: textOrNull(row['sentAt']) };
}
