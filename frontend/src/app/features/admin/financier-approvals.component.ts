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

import { ApiFailure, Page, commonFailureMessage, failureOf } from '../../shared/api-failure';
import { formatInstant } from '../seller/invoice-format';
import { AdminNavComponent } from './admin-nav.component';
import { AdminUser, AdminUsersService, UserStatus, userStatusOf } from './admin-users.service';
import { Notice, pageLink, pageParam } from './admin-shared';

type ListState = { kind: 'loading' } | { kind: 'loaded'; result: Page<AdminUser> } | { kind: 'error'; message: string };

/** What happened to a row since the list was loaded, as reported by auth-service. */
type Outcome = { kind: 'status'; status: UserStatus } | { kind: 'missing' };

const STATUS_LABELS: Readonly<Record<UserStatus, { label: string; badge: string }>> = {
  ACTIVE: { label: 'Approved', badge: 'badge badge-success' },
  PENDING_APPROVAL: { label: 'Pending approval', badge: 'badge badge-warning' },
  DISABLED: { label: 'Disabled', badge: 'badge' },
};

const SERVICE = 'account service';

/** Financier accounts waiting for an administrator. Approving lets the financier sign in. */
@Component({
  selector: 'ifp-financier-approvals',
  imports: [RouterLink, AdminNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './financier-approvals.component.html',
  styleUrl: './admin.scss',
})
export class FinancierApprovalsComponent {
  private readonly api = inject(AdminUsersService);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);

  /** Query parameter, bound by the router. */
  readonly page = input<string>();

  protected readonly formatInstant = formatInstant;
  protected readonly pageLink = pageLink;

  protected readonly pageIndex = computed(() => pageParam(this.page()));
  protected readonly state = signal<ListState>({ kind: 'loading' });
  protected readonly outcomes = signal<ReadonlyMap<string, Outcome>>(new Map());
  protected readonly acting = signal<string | null>(null);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly loading = computed(() => this.state().kind === 'loading');

  protected readonly result = computed(() => {
    const state = this.state();
    return state.kind === 'loaded' ? state.result : null;
  });
  protected readonly errorMessage = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
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

  protected outcomeOf(user: AdminUser): Outcome | null {
    return this.outcomes().get(user.id) ?? null;
  }

  protected badgeFor(user: AdminUser): { label: string; badge: string } {
    const outcome = this.outcomeOf(user);
    if (outcome?.kind === 'missing') {
      return { label: 'No longer exists', badge: 'badge' };
    }
    return STATUS_LABELS[outcome?.status ?? user.status];
  }

  protected canApprove(user: AdminUser): boolean {
    return this.acting() === null && this.outcomeOf(user) === null;
  }

  protected async approve(user: AdminUser): Promise<void> {
    if (!this.canApprove(user)) {
      return;
    }
    this.acting.set(user.id);
    this.notice.set(null);
    try {
      const updated = await this.api.activate(user.id);
      this.mark(user.id, { kind: 'status', status: updated.status });
      this.showNotice(
        updated.status === 'ACTIVE'
          ? { tone: 'success', message: `${user.displayName} is approved and can now sign in.` }
          : { tone: 'warning', message: `${user.displayName} is now ${STATUS_LABELS[updated.status].label.toLowerCase()}.` },
      );
    } catch (error) {
      this.handleFailure(user, failureOf(error));
    } finally {
      this.acting.set(null);
    }
  }

  private handleFailure(user: AdminUser, failure: ApiFailure): void {
    if (failure.kind === 'conflict' && failure.code === 'USER_NOT_PENDING_APPROVAL') {
      const status = userStatusOf(failure.currentStatus);
      if (status) {
        this.mark(user.id, { kind: 'status', status });
      }
      const now = status ? ` It is now ${STATUS_LABELS[status].label.toLowerCase()}.` : '';
      this.showNotice({ tone: 'warning', message: `${user.displayName} is no longer waiting for approval.${now}` });
      return;
    }
    if (failure.kind === 'not-found') {
      this.mark(user.id, { kind: 'missing' });
      this.showNotice({ tone: 'warning', message: `The account for ${user.displayName} no longer exists.` });
      return;
    }
    if (failure.kind === 'forbidden') {
      this.showNotice({ tone: 'danger', message: 'You do not have permission to approve accounts.' });
      return;
    }
    if (failure.kind === 'conflict') {
      this.showNotice({
        tone: 'warning',
        message: `${user.displayName}'s account was changed by another request at the same moment. The list has been refreshed; check it and try again.`,
      });
      void this.load(this.pageIndex(), { keepNotice: true });
      return;
    }
    this.showNotice({
      tone: 'danger',
      message: `${user.displayName} was not approved. ${commonFailureMessage(failure, SERVICE)}`,
    });
  }

  private mark(id: string, outcome: Outcome): void {
    this.outcomes.update((current) => new Map(current).set(id, outcome));
  }

  private async load(page: number, options: { keepNotice?: boolean } = {}): Promise<void> {
    const request = ++this.request;
    this.state.set({ kind: 'loading' });
    if (!options.keepNotice) {
      this.notice.set(null);
    }
    try {
      const result = await this.api.pendingFinanciers(page);
      if (request !== this.request) {
        return;
      }
      if (result.content.length === 0 && page > 0 && result.totalPages > 0) {
        void this.router.navigate([], { queryParams: pageLink(result.totalPages - 1), queryParamsHandling: 'merge' });
        return;
      }
      this.outcomes.set(new Map());
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
