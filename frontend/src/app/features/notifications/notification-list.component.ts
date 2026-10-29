import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Page, commonFailureMessage, failureOf } from '../../shared/api-failure';
import { formatInstant } from '../seller/invoice-format';
import { NotificationApiService, NotificationItem } from './notification-api.service';
import { NotificationBadgeService } from './notification-badge.service';

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; result: Page<NotificationItem>; fresh: ReadonlySet<string> }
  | { kind: 'error'; message: string };

const SKIP_REASONS: Readonly<Record<string, string>> = {
  RECIPIENT_UNKNOWN: 'no email address was on file yet',
};

function pageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 && page <= 10_001 ? page - 1 : 0;
}

/** The signed-in user's notifications, for every role. notification-service has no read state to change. */
@Component({
  selector: 'ifp-notification-list',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './notification-list.component.html',
  styleUrl: './notification-list.component.scss',
})
export class NotificationListComponent {
  private readonly api = inject(NotificationApiService);
  private readonly badge = inject(NotificationBadgeService);
  private readonly router = inject(Router);

  /** Query parameter, bound by the router. */
  readonly page = input<string>();

  protected readonly formatInstant = formatInstant;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly loading = computed(() => this.state().kind === 'loading');
  protected readonly loaded = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state : null;
  });
  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
  });
  protected readonly range = computed(() => {
    const result = this.loaded()?.result;
    if (!result || result.content.length === 0) {
      return null;
    }
    const { page, size, totalElements, content } = result;
    return { from: page * size + 1, to: page * size + content.length, total: totalElements };
  });

  private request = 0;

  constructor() {
    effect(() => {
      const page = this.pageIndex();
      untracked(() => void this.load(page));
    });
  }

  protected refresh(): void {
    void this.load(this.pageIndex());
  }

  protected pageLink(index: number): Record<string, number | null> {
    return { page: index === 0 ? null : index + 1 };
  }

  protected delivery(item: NotificationItem): string {
    if (item.status === 'SENT') {
      return item.sentAt ? `Emailed ${formatInstant(item.sentAt)}` : 'Emailed';
    }
    const reason = item.skipReason ? SKIP_REASONS[item.skipReason] : undefined;
    return reason ? `Not emailed: ${reason}` : 'Not emailed';
  }

  private async load(page: number): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    try {
      const result = await this.api.list(page);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        void this.router.navigate([], { queryParams: this.pageLink(result.totalPages - 1), queryParamsHandling: 'merge' });
        return;
      }
      const fresh = new Set(result.content.filter((item) => this.badge.isNew(item)).map((item) => item.id));
      if (page === 0) {
        this.badge.markSeen(result.content);
      }
      this.state.set({ kind: 'loaded', result, fresh });
    } catch (error) {
      if (request === this.request) {
        this.state.set({ kind: 'error', message: commonFailureMessage(failureOf(error), 'notification service') });
      }
    }
  }
}
