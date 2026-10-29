import { DOCUMENT, Injectable, InjectionToken, computed, inject, signal } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { NOTIFICATION_PAGE_SIZE, NotificationApiService, NotificationItem } from './notification-api.service';

/** Where the "last seen" marker is kept. It holds a timestamp per user ID, never tokens or message content. */
export const NOTIFICATION_SEEN_STORAGE = new InjectionToken<Storage>('NOTIFICATION_SEEN_STORAGE', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).defaultView!.localStorage,
});

export const SEEN_KEY_PREFIX = 'ifp.notifications.seen.';

/** Background checks happen at most this often; opening the notifications page always checks. */
const MIN_INTERVAL_MS = 30_000;

/** Server instants may or may not carry fractional seconds, so they are compared as times, not as text. */
function isAfter(instant: string, marker: string | null): boolean {
  const time = Date.parse(instant);
  const seen = marker === null ? Number.NaN : Date.parse(marker);
  return Number.isNaN(seen) || (!Number.isNaN(time) && time > seen);
}

/**
 * Counts notifications that arrived since the user last opened the notifications page in this browser.
 * notification-service has no read state, so this is a per-device "new" count rather than a server-side unread count.
 */
@Injectable({ providedIn: 'root' })
export class NotificationBadgeService {
  private readonly api = inject(NotificationApiService);
  private readonly auth = inject(AuthService);
  private readonly storage = inject(NOTIFICATION_SEEN_STORAGE);

  private readonly state = signal<{ userId: string; count: number; more: boolean } | null>(null);
  private lastCheck = 0;
  private inFlight: Promise<void> | null = null;

  /** The badge text, or null when there is nothing new (or the count is unknown). */
  readonly label = computed(() => {
    const state = this.state();
    const user = this.auth.user();
    if (!state || !user || state.userId !== user.id || state.count === 0) {
      return null;
    }
    return state.more ? `${state.count}+` : String(state.count);
  });

  /** Checks for new notifications unless a check ran recently. Failures leave the badge as it was. */
  refreshIfStale(): Promise<void> {
    return Date.now() - this.lastCheck < MIN_INTERVAL_MS ? (this.inFlight ?? Promise.resolve()) : this.refresh();
  }

  refresh(): Promise<void> {
    this.inFlight ??= this.check().finally(() => (this.inFlight = null));
    return this.inFlight;
  }

  /** The user is looking at these notifications (newest first): nothing up to the newest one counts as new any more. */
  markSeen(newestFirst: readonly NotificationItem[]): void {
    const user = this.auth.user();
    if (!user) {
      return;
    }
    const newest = newestFirst[0]?.createdAt;
    if (newest && isAfter(newest, this.seenMarker(user.id))) {
      try {
        this.storage.setItem(SEEN_KEY_PREFIX + user.id, newest);
      } catch {
        // Storage may be full or blocked; the badge then simply keeps counting.
      }
    }
    this.state.set({ userId: user.id, count: 0, more: false });
  }

  /** Whether a notification arrived after the user last opened the page. */
  isNew(item: NotificationItem): boolean {
    const user = this.auth.user();
    return !!user && isAfter(item.createdAt, this.seenMarker(user.id));
  }

  seenMarker(userId: string): string | null {
    try {
      return this.storage.getItem(SEEN_KEY_PREFIX + userId);
    } catch {
      return null;
    }
  }

  private async check(): Promise<void> {
    const user = this.auth.user();
    if (!user) {
      this.state.set(null);
      return;
    }
    this.lastCheck = Date.now();
    try {
      const page = await this.api.list(0, NOTIFICATION_PAGE_SIZE);
      if (this.auth.user()?.id !== user.id) {
        return;
      }
      const seen = this.seenMarker(user.id);
      const count = page.content.filter((item) => isAfter(item.createdAt, seen)).length;
      this.state.set({ userId: user.id, count, more: count === page.content.length && page.totalElements > count });
    } catch {
      // A failed background check must not disturb the page; the next navigation tries again.
    }
  }
}
