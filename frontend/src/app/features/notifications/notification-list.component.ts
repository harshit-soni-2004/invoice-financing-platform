import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Page, commonFailureMessage, failureOf } from '../../shared/api-failure';
import { formatInstant } from '../seller/invoice-format';
import { NotificationApiService, NotificationItem } from './notification-api.service';
import { NotificationBadgeService } from './notification-badge.service';

type ListState = { kind: 'loading' } | { kind: 'loaded'; result: Page<NotificationItem> } | { kind: 'error'; message: string };

interface Notice {
  tone: 'success' | 'danger';
  message: string;
}

const SERVICE = 'notification service';

const SKIP_REASONS: Readonly<Record<string, string>> = {
  RECIPIENT_UNKNOWN: 'no email address was on file yet',
};

function pageParam(value: string | undefined): number {
  const page = Number(value);
  return Number.isInteger(page) && page >= 1 && page <= 10_001 ? page - 1 : 0;
}

/** The signed-in user's notifications, for every role, with notification-service's per-user read state. */
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
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Query parameter, bound by the router. */
  readonly page = input<string>();

  protected readonly formatInstant = formatInstant;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly marking = signal<ReadonlySet<string>>(new Set());
  protected readonly markingAll = signal(false);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly unreadTotal = this.badge.count;
  protected readonly loading = computed(() => this.state().kind === 'loading');
  protected readonly result = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.result : null;
  });
  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
  });
  protected readonly range = computed(() => {
    const result = this.result();
    if (!result || result.content.length === 0) {
      return null;
    }
    const { page, size, totalElements, content } = result;
    return { from: page * size + 1, to: page * size + content.length, total: totalElements };
  });
  /** Unread notifications may sit on other pages, so the server's total counts too. */
  protected readonly canMarkAll = computed(() => {
    const anyUnreadHere = this.result()?.content.some((item) => item.readAt === null) ?? false;
    return !this.markingAll() && (anyUnreadHere || (this.unreadTotal() ?? 0) > 0);
  });

  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');
  private request = 0;

  constructor() {
    effect(() => {
      const page = this.pageIndex();
      untracked(() => void this.load(page));
    });
  }

  protected refresh(): void {
    this.notice.set(null);
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

  protected isMarking(item: NotificationItem): boolean {
    return this.marking().has(item.id);
  }

  protected async markRead(item: NotificationItem): Promise<void> {
    if (item.readAt !== null || this.isMarking(item) || this.markingAll()) {
      return;
    }
    this.marking.update((ids) => new Set(ids).add(item.id));
    this.notice.set(null);
    try {
      const updated = await this.api.markRead(item.id);
      this.replace(updated);
      void this.badge.recount();
      afterNextRender(() => this.focusItem(item.id), { injector: this.injector });
    } catch (error) {
      const failure = failureOf(error);
      if (failure.kind === 'not-found') {
        this.showNotice({ tone: 'danger', message: 'That notification no longer exists. The list has been refreshed.' });
        void this.load(this.pageIndex(), { keepNotice: true });
      } else {
        this.showNotice({ tone: 'danger', message: `The notification was not marked as read. ${commonFailureMessage(failure, SERVICE)}` });
      }
    } finally {
      this.marking.update((ids) => {
        const next = new Set(ids);
        next.delete(item.id);
        return next;
      });
    }
  }

  protected async markAllRead(): Promise<void> {
    if (!this.canMarkAll()) {
      return;
    }
    this.markingAll.set(true);
    this.notice.set(null);
    try {
      const marked = await this.api.markAllRead();
      void this.badge.recount();
      await this.load(this.pageIndex(), { keepNotice: true });
      this.showNotice({
        tone: 'success',
        message:
          marked === 0
            ? 'Nothing was unread.'
            : `Marked ${marked} ${marked === 1 ? 'notification' : 'notifications'} as read.`,
      });
    } catch (error) {
      this.showNotice({
        tone: 'danger',
        message: `Your notifications were not marked as read. ${commonFailureMessage(failureOf(error), SERVICE)}`,
      });
    } finally {
      this.markingAll.set(false);
    }
  }

  private replace(updated: NotificationItem): void {
    this.state.update((state) =>
      state.kind === 'loaded'
        ? { ...state, result: { ...state.result, content: state.result.content.map((item) => (item.id === updated.id ? updated : item)) } }
        : state,
    );
  }

  private focusItem(id: string): void {
    this.host.nativeElement.querySelector<HTMLElement>(`[data-notification-id="${CSS.escape(id)}"]`)?.focus();
  }

  private async load(page: number, options: { keepNotice?: boolean } = {}): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    if (!options.keepNotice) {
      this.notice.set(null);
    }
    void this.badge.refresh();
    try {
      const result = await this.api.list(page);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        void this.router.navigate([], { queryParams: this.pageLink(result.totalPages - 1), queryParamsHandling: 'merge' });
        return;
      }
      this.state.set({ kind: 'loaded', result });
    } catch (error) {
      if (request === this.request) {
        this.state.set({ kind: 'error', message: commonFailureMessage(failureOf(error), SERVICE) });
      }
    }
  }

  private showNotice(notice: Notice): void {
    this.notice.set(notice);
    afterNextRender(() => this.noticeBox()?.nativeElement.focus(), { injector: this.injector });
  }
}
