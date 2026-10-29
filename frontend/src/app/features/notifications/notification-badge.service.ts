import { Injectable, computed, inject, signal } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { NotificationApiService } from './notification-api.service';

/** Background checks happen at most this often; opening the notifications page always checks. */
const MIN_INTERVAL_MS = 30_000;

/** Larger counts are shown as "99+" so the header badge keeps its size. */
const MAX_SHOWN = 99;

/** The signed-in user's unread notification count, as notification-service reports it (shared across devices). */
@Injectable({ providedIn: 'root' })
export class NotificationBadgeService {
  private readonly api = inject(NotificationApiService);
  private readonly auth = inject(AuthService);

  private readonly state = signal<{ userId: string; count: number } | null>(null);
  private lastCheck = 0;
  private inFlight: Promise<void> | null = null;
  private generation = 0;

  /** The unread count for the signed-in user, or null while it is unknown (not checked yet, or the check failed). */
  readonly count = computed(() => {
    const state = this.state();
    const user = this.auth.user();
    return state && user && state.userId === user.id ? state.count : null;
  });

  /** The badge text, or null when nothing is unread (or the count is unknown). */
  readonly label = computed(() => {
    const count = this.count();
    if (count === null || count === 0) {
      return null;
    }
    return count > MAX_SHOWN ? `${MAX_SHOWN}+` : String(count);
  });

  /** Checks the count unless a check ran recently. Failures leave the badge as it was. */
  refreshIfStale(): Promise<void> {
    return Date.now() - this.lastCheck < MIN_INTERVAL_MS ? (this.inFlight ?? Promise.resolve()) : this.refresh();
  }

  refresh(): Promise<void> {
    if (!this.inFlight) {
      const check = this.check().finally(() => {
        if (this.inFlight === check) {
          this.inFlight = null;
        }
      });
      this.inFlight = check;
    }
    return this.inFlight;
  }

  /** After the user changed read state: a check already in flight may predate the change, so its answer is dropped. */
  recount(): Promise<void> {
    this.generation++;
    this.inFlight = null;
    return this.refresh();
  }

  private async check(): Promise<void> {
    const user = this.auth.user();
    if (!user) {
      this.state.set(null);
      return;
    }
    const generation = this.generation;
    this.lastCheck = Date.now();
    try {
      const count = await this.api.unreadCount();
      if (generation === this.generation && this.auth.user()?.id === user.id) {
        this.state.set({ userId: user.id, count });
      }
    } catch {
      // A failed background check must not disturb the page; the next navigation tries again.
    }
  }
}
