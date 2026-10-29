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
import { AdminUser, AdminUsersService, REJECT_USER_REASON_MAX, UserStatus, userStatusOf } from './admin-users.service';
import { Notice, pageLink, pageParam } from './admin-shared';

type ListState = { kind: 'loading' } | { kind: 'loaded'; result: Page<AdminUser> } | { kind: 'error'; message: string };

/** What happened to a row since the list was loaded, as reported by auth-service. */
type Outcome = { kind: 'status'; status: UserStatus } | { kind: 'missing' };

type Decision = 'approve' | 'reject';

const STATUS_LABELS: Readonly<Record<UserStatus, { label: string; badge: string }>> = {
  ACTIVE: { label: 'Approved', badge: 'badge badge-success' },
  PENDING_APPROVAL: { label: 'Pending approval', badge: 'badge badge-warning' },
  DISABLED: { label: 'Disabled', badge: 'badge' },
  REJECTED: { label: 'Rejected', badge: 'badge badge-danger' },
};

const SERVICE = 'account service';

/** Failures the administrator can retry from the open rejection dialog; anything else closes it. */
const RETRY_IN_DIALOG: ReadonlySet<ApiFailure['kind']> = new Set(['invalid', 'network', 'unavailable', 'rate-limited', 'unexpected']);

/** Financier accounts waiting for an administrator. Approving lets the financier sign in; rejecting is final. */
@Component({
  selector: 'ifp-financier-approvals',
  imports: [RouterLink, AdminNavComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './financier-approvals.component.html',
  styleUrls: ['./admin.scss', '../seller/seller-dialog.scss'],
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
  protected readonly decision = signal<Decision | null>(null);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly rejectTarget = signal<AdminUser | null>(null);
  protected readonly rejectReason = signal('');
  protected readonly rejectError = signal<string | null>(null);
  protected readonly rejectReasonMax = REJECT_USER_REASON_MAX;
  protected readonly rejecting = computed(() => this.decision() === 'reject' && this.acting() !== null);
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
  private readonly rejectDialog = viewChild<ElementRef<HTMLDialogElement>>('rejectDialog');
  private readonly rejectAlert = viewChild<ElementRef<HTMLElement>>('rejectAlert');
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

  protected canDecide(user: AdminUser): boolean {
    return this.acting() === null && this.outcomeOf(user) === null;
  }

  protected busyWith(user: AdminUser, decision: Decision): boolean {
    return this.acting() === user.id && this.decision() === decision;
  }

  protected async approve(user: AdminUser): Promise<void> {
    if (!this.canDecide(user)) {
      return;
    }
    this.acting.set(user.id);
    this.decision.set('approve');
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
      this.handleFailure(user, failureOf(error), 'approve');
    } finally {
      this.acting.set(null);
      this.decision.set(null);
    }
  }

  protected openReject(user: AdminUser): void {
    if (!this.canDecide(user)) {
      return;
    }
    this.rejectTarget.set(user);
    this.rejectReason.set('');
    this.rejectError.set(null);
    this.notice.set(null);
    afterNextRender(() => this.rejectDialog()?.nativeElement.showModal(), { injector: this.injector });
  }

  protected closeReject(): void {
    if (!this.rejecting()) {
      this.rejectDialog()?.nativeElement.close();
    }
  }

  /** Escape must not close the dialog while the request is in flight. */
  protected onRejectDialogCancel(event: Event): void {
    if (this.rejecting()) {
      event.preventDefault();
    }
  }

  protected onRejectDialogClose(): void {
    this.rejectTarget.set(null);
  }

  protected onRejectReason(event: Event): void {
    this.rejectReason.set((event.target as HTMLTextAreaElement).value);
    this.rejectError.set(null);
  }

  protected async confirmReject(): Promise<void> {
    const user = this.rejectTarget();
    if (!user || this.acting() !== null) {
      return;
    }
    const reason = this.rejectReason().trim();
    if (reason.length > REJECT_USER_REASON_MAX) {
      this.showRejectError(`Keep the reason to ${REJECT_USER_REASON_MAX} characters or fewer.`);
      return;
    }
    this.acting.set(user.id);
    this.decision.set('reject');
    this.rejectError.set(null);
    try {
      const updated = await this.api.reject(user.id, reason === '' ? null : reason);
      this.finishReject();
      this.mark(user.id, { kind: 'status', status: updated.status });
      this.showNotice(
        updated.status === 'REJECTED'
          ? { tone: 'success', message: `${user.displayName}'s application is rejected. The account can never sign in.` }
          : { tone: 'warning', message: `${user.displayName} is now ${STATUS_LABELS[updated.status].label.toLowerCase()}.` },
      );
    } catch (error) {
      const failure = failureOf(error);
      if (RETRY_IN_DIALOG.has(failure.kind)) {
        this.finishReject({ keepOpen: true });
        this.showRejectError(
          failure.kind === 'invalid'
            ? `The reason was not accepted. Keep it to ${REJECT_USER_REASON_MAX} characters or fewer.`
            : `${user.displayName} was not rejected. ${commonFailureMessage(failure, SERVICE)}`,
        );
        return;
      }
      this.finishReject();
      this.handleFailure(user, failure, 'reject');
    }
  }

  private finishReject(options: { keepOpen?: boolean } = {}): void {
    this.acting.set(null);
    this.decision.set(null);
    if (!options.keepOpen) {
      this.rejectDialog()?.nativeElement.close();
    }
  }

  private showRejectError(message: string): void {
    this.rejectError.set(message);
    afterNextRender(() => this.rejectAlert()?.nativeElement.focus(), { injector: this.injector });
  }

  private handleFailure(user: AdminUser, failure: ApiFailure, decision: Decision): void {
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
      this.showNotice({ tone: 'danger', message: `You do not have permission to ${decision} accounts.` });
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
      message: `${user.displayName} was not ${decision === 'approve' ? 'approved' : 'rejected'}. ${commonFailureMessage(failure, SERVICE)}`,
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
